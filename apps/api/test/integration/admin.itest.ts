import { randomUUID } from 'node:crypto';
import { NestFactory } from '@nestjs/core';
import type { AuditEvent, KeyStatus, RotationRun } from '@scanova/contracts/admin';
import type { Tokens, User } from '@scanova/contracts/auth';
import type { Document } from '@scanova/contracts/documents';
import { ciphertextKeyVersion } from '@scanova/kms/kms';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AppModule } from '../../src/app.module.js';
import { runCli } from '../../src/cli/run.js';
import { AppLogger } from '@scanova/config/logger';
import {
  DEVICE,
  SERVICE_HEADER,
  bucket,
  closeAllApps,
  closeInspectors,
  createAccount,
  createAdmin,
  createDocument,
  createMember,
  keysDb,
  mainDb,
  startApp,
  state,
  testConfig,
  uniqueEmail,
  vault,
  workerKeyring,
  type ApiResponse,
  type TestApp,
  type TestUser,
} from './harness.js';

let api: TestApp;
let admin: TestUser;

beforeAll(async () => {
  api = await startApp();
  admin = await createAdmin(api);
});

afterAll(async () => {
  await closeAllApps();
  await closeInspectors();
});

const ADMIN = '/internal/admin/v1';

/** A fingerprint of every row in the main database. */
async function mainDatabaseFingerprint(): Promise<Record<string, string>> {
  const tables = [
    'users',
    'sessions',
    'spent_refresh_tokens',
    'documents',
    'document_texts',
    'file_objects',
    'search_tokens',
    'audit_events',
  ];
  const result: Record<string, string> = {};
  for (const table of tables) {
    const rows = await mainDb().$queryRawUnsafe<{ rows: bigint; digest: string | null }[]>(
      `SELECT count(*) AS rows, md5(coalesce(string_agg(md5(t::text), '' ORDER BY t.id), '')) AS digest FROM ${table} t`,
    );
    result[table] = `${rows[0]!.rows}:${rows[0]!.digest}`;
  }
  const jobs = await mainDb().$queryRawUnsafe<{ rows: bigint }[]>(
    'SELECT count(*) AS rows FROM pgboss.job',
  );
  result['pgboss.job'] = String(jobs[0]!.rows);
  return result;
}

async function expectReadable(
  user: TestUser,
  documents: { id: string; file: Buffer | null }[],
  client = user.client,
): Promise<void> {
  for (const document of documents) {
    const metadata = await client.get<Document>(`/api/v1/documents/${document.id}`);
    expect(metadata.status).toBe(200);
    expect(metadata.body!.data.title).toBe('Test document');
    if (document.file !== null) {
      const download = await client.get(`/api/v1/documents/${document.id}/file`);
      expect(download.status).toBe(200);
      expect(download.raw.equals(document.file)).toBe(true);
    }
  }
}

describe('/internal/* requires the web service token', () => {
  it('rejects a missing or wrong token on every internal route, before anything else', async () => {
    const routes: [string, string, unknown?][] = [
      ['POST', '/internal/web/v1/auth/login', { email: admin.email, password: admin.password }],
      ['GET', `${ADMIN}/users`],
      ['POST', `${ADMIN}/users`, { email: uniqueEmail(), name: 'X', userType: 'MEMBER' }],
      ['PATCH', `${ADMIN}/users/${admin.id}`, { name: 'X' }],
      ['POST', `${ADMIN}/users/${admin.id}/reset-password`],
      ['POST', `${ADMIN}/users/${admin.id}/revoke-sessions`],
      ['DELETE', `${ADMIN}/users/${admin.id}`],
      ['GET', `${ADMIN}/keys`],
      ['POST', `${ADMIN}/keys/rotations`, { scope: 'JWT_SIGNING' }],
      ['GET', `${ADMIN}/keys/rotations`],
      ['GET', `${ADMIN}/audit-events`],
    ];
    // A perfectly valid admin access token, but the caller is not the web app.
    const withoutToken = api.client.as(admin.tokens);
    const wrongToken = withoutToken.withHeaders({
      'x-scanova-service-token': `${state.openbao.rootToken}-not-it`,
    });
    const almost = withoutToken.withHeaders({
      'x-scanova-service-token': `${api.config.auth.webServiceToken.slice(0, -1)}X`,
    });
    const empty = withoutToken.withHeaders({ 'x-scanova-service-token': '' });
    for (const [method, path, json] of routes) {
      for (const client of [withoutToken, wrongToken, almost, empty]) {
        const response = await client.request(method, path, json === undefined ? {} : { json });
        expect(response.status).toBe(401);
        expect(response.body).toEqual({
          data: null,
          error: { code: 'UNAUTHENTICATED', message: 'Sign in to continue.' },
          meta: { nextCursor: null },
        });
      }
    }
    // Nothing happened: the admin still exists and can sign in.
    expect((await admin.client.get(`${ADMIN}/users`)).status).toBe(200);
  });

  it('requires typ=ADMIN and cli=web on admin routes, in addition to the service token', async () => {
    const member = await createMember(api);
    const web = api.client.withHeaders(SERVICE_HEADER);
    // A member, signed in on the phone or on the web, is FORBIDDEN.
    expect((await web.as(member.tokens).get(`${ADMIN}/users`)).body?.error?.code).toBe('FORBIDDEN');
    const memberOnWeb = await web.post<Tokens>('/internal/web/v1/auth/login', {
      email: member.email,
      password: member.password,
    });
    expect(memberOnWeb.status).toBe(200);
    expect((await web.as(memberOnWeb.body!.data).get(`${ADMIN}/keys`)).body?.error?.code).toBe(
      'FORBIDDEN',
    );
    // The service token alone is not enough either.
    expect((await web.get(`${ADMIN}/users`)).body?.error?.code).toBe('UNAUTHENTICATED');
    // An admin who has not replaced the temporary password yet cannot administer.
    const fresh = await createAccount(api, 'ADMIN');
    const pending = await web.post<Tokens>('/internal/web/v1/auth/login', {
      email: fresh.email,
      password: fresh.temporaryPassword,
    });
    expect((await web.as(pending.body!.data).get(`${ADMIN}/users`)).body?.error?.code).toBe(
      'PASSWORD_CHANGE_REQUIRED',
    );
  });
});

describe('user administration', () => {
  it('creates a user with a one-time temporary password and lists accounts', async () => {
    const email = uniqueEmail('new');
    const created = await admin.client.post<{ user: User; temporaryPassword: string }>(
      `${ADMIN}/users`,
      { email, name: 'New Member', userType: 'MEMBER' },
    );
    expect(created.status).toBe(201);
    expect(created.body!.data.user).toMatchObject({
      email,
      name: 'New Member',
      userType: 'MEMBER',
      status: 'ACTIVE',
      mustChangePassword: true,
      storageUsedBytes: 0,
    });
    expect(created.body!.data.temporaryPassword).toMatch(/^[A-HJ-NP-Za-km-z2-9]{20}$/u);

    const duplicate = await admin.client.post(`${ADMIN}/users`, {
      email: email.toUpperCase(),
      name: 'Again',
      userType: 'MEMBER',
    });
    expect(duplicate.status).toBe(409);
    expect(duplicate.body?.error?.code).toBe('ID_CONFLICT');
    // The failed attempt left no usable keys behind.
    expect(
      await keysDb().userKey.count({
        where: { state: 'ACTIVE', userId: created.body!.data.user.id },
      }),
    ).toBe(1);

    const login = await api.client.post<Tokens>('/api/v1/auth/login', {
      email,
      password: created.body!.data.temporaryPassword,
      client: 'mobile',
      device: DEVICE,
    });
    expect(login.body!.data.user.mustChangePassword).toBe(true);

    const seen: User[] = [];
    let cursor: string | null = null;
    do {
      const page: ApiResponse<User[]> = await admin.client.get<User[]>(
        `${ADMIN}/users?limit=100${cursor === null ? '' : `&cursor=${cursor}`}`,
      );
      expect(page.status).toBe(200);
      seen.push(...page.body!.data);
      cursor = page.body!.meta!.nextCursor;
    } while (cursor !== null);
    expect(seen.find((user) => user.id === created.body!.data.user.id)?.email).toBe(email);
    expect(seen.find((user) => user.id === admin.id)?.userType).toBe('ADMIN');
    expect(new Set(seen.map((user) => user.id)).size).toBe(seen.length);
  });

  it('renames, disables and re-enables a user, revoking sessions on disable', async () => {
    const member = await createMember(api);
    const renamed = await admin.client.patch<User>(`${ADMIN}/users/${member.id}`, {
      name: 'Renamed',
    });
    expect(renamed.body!.data.name).toBe('Renamed');

    const disabled = await admin.client.patch<User>(`${ADMIN}/users/${member.id}`, {
      status: 'DISABLED',
    });
    expect(disabled.body!.data.status).toBe('DISABLED');
    expect((await member.client.get('/api/v1/me')).status).toBe(401);
    expect(
      (
        await api.client.post('/api/v1/auth/login', {
          email: member.email,
          password: member.password,
          client: 'mobile',
          device: DEVICE,
        })
      ).body?.error?.code,
    ).toBe('ACCOUNT_DISABLED');

    await admin.client.patch(`${ADMIN}/users/${member.id}`, { status: 'ACTIVE' });
    expect(
      (
        await api.client.post('/api/v1/auth/login', {
          email: member.email,
          password: member.password,
          client: 'mobile',
          device: DEVICE,
        })
      ).status,
    ).toBe(200);
    expect(
      (await admin.client.patch(`${ADMIN}/users/${randomUUID()}`, { name: 'Ghost' })).status,
    ).toBe(404);
    expect((await admin.client.patch(`${ADMIN}/users/${member.id}`, {})).status).toBe(400);
  });

  it('protects the administration from locking itself out', async () => {
    for (const response of [
      await admin.client.patch(`${ADMIN}/users/${admin.id}`, { status: 'DISABLED' }),
      await admin.client.patch(`${ADMIN}/users/${admin.id}`, { userType: 'MEMBER' }),
      await admin.client.delete(`${ADMIN}/users/${admin.id}`),
    ]) {
      expect(response.status).toBe(403);
      expect(response.body?.error?.code).toBe('FORBIDDEN');
    }
    expect((await admin.client.get(`${ADMIN}/users`)).status).toBe(200);
  });

  it('resets a password: temporary password, all sessions revoked, user key rotated', async () => {
    const member = await createMember(api);
    const documents = [await createDocument(member), await createDocument(member, { file: null })];
    const reset = await admin.client.post<{ temporaryPassword: string }>(
      `${ADMIN}/users/${member.id}/reset-password`,
    );
    expect(reset.status).toBe(200);

    expect((await member.client.get('/api/v1/me')).body?.error?.code).toBe('SESSION_REVOKED');
    expect(
      (
        await api.client.post('/api/v1/auth/login', {
          email: member.email,
          password: member.password,
          client: 'mobile',
          device: DEVICE,
        })
      ).status,
    ).toBe(401);
    const login = await api.client.post<Tokens>('/api/v1/auth/login', {
      email: member.email,
      password: reset.body!.data.temporaryPassword,
      client: 'mobile',
      device: DEVICE,
    });
    expect(login.body!.data.user.mustChangePassword).toBe(true);

    // A new user-key version is active at once; the re-wrap is queued for the worker.
    const versions = await keysDb().userKey.findMany({
      where: { userId: member.id },
      orderBy: { version: 'asc' },
    });
    expect(versions.map((key) => [key.version, key.state])).toEqual([
      [1, 'RETIRED'],
      [2, 'ACTIVE'],
    ]);
    const run = await keysDb().keyRotationRun.findFirstOrThrow({
      where: { scope: 'USER_KEY', target: member.id },
    });
    expect(run).toMatchObject({
      trigger: 'PASSWORD_RESET',
      state: 'PENDING',
      requestedBy: admin.id,
    });
    const jobs = await mainDb().$queryRaw<
      { data: unknown }[]
    >`SELECT data FROM pgboss.job WHERE name = 'rotation.run' AND data->>'runId' = ${run.publicId}`;
    expect(jobs).toEqual([{ data: { runId: run.publicId } }]);

    // Once the user has a new password, everything is still there.
    const changed = await api.client.as(login.body!.data).post<Tokens>('/api/v1/me/password', {
      currentPassword: reset.body!.data.temporaryPassword,
      newPassword: 'after-the-reset-password',
    });
    await expectReadable(member, documents, api.client.as(changed.body!.data));

    const revoke = await admin.client.post(`${ADMIN}/users/${member.id}/revoke-sessions`);
    expect(revoke.body?.data).toEqual({ success: true });
    expect((await api.client.as(changed.body!.data).get('/api/v1/me')).body?.error?.code).toBe(
      'SESSION_REVOKED',
    );
  });

  it('changes a user type: the user key moves to the other master key and is rotated', async () => {
    const member = await createMember(api);
    const documents = [await createDocument(member)];
    const promoted = await admin.client.patch<User>(`${ADMIN}/users/${member.id}`, {
      userType: 'ADMIN',
    });
    expect(promoted.body!.data.userType).toBe('ADMIN');

    const keys = await keysDb().userKey.findMany({
      where: { userId: member.id },
      orderBy: { version: 'asc' },
    });
    expect(keys.map((key) => [key.version, key.state, key.masterKeyName])).toEqual([
      [1, 'RETIRED', state.openbao.keys.userTypeAdmin],
      [2, 'ACTIVE', state.openbao.keys.userTypeAdmin],
    ]);
    // The member master key can no longer unwrap this user's key.
    await expect(
      vault('api').decrypt(state.openbao.keys.userTypeMember, keys[1]!.wrappedKey!, member.id),
    ).rejects.toMatchObject({ code: 'REJECTED' });

    // Old tokens are gone; as an admin the user is web-only now.
    expect((await member.client.get('/api/v1/me')).body?.error?.code).toBe('SESSION_REVOKED');
    expect(
      (
        await api.client.post('/api/v1/auth/login', {
          email: member.email,
          password: member.password,
          client: 'mobile',
          device: DEVICE,
        })
      ).body?.error?.code,
    ).toBe('INVALID_CREDENTIALS');
    const web = api.client.withHeaders(SERVICE_HEADER);
    const login = await web.post<Tokens>('/internal/web/v1/auth/login', {
      email: member.email,
      password: member.password,
    });
    expect(login.status).toBe(200);

    const run = await keysDb().keyRotationRun.findFirstOrThrow({
      where: { scope: 'USER_KEY', target: member.id },
    });
    expect(run.trigger).toBe('USER_TYPE_CHANGE');
    await workerKeyring().rotation.run(run.publicId);
    const fresh = await startApp();
    await expectReadable(member, documents, fresh.client.as(login.body!.data));
    await fresh.close();
  });
});

describe('key status and rotation', () => {
  it('reports key versions and pending work without key material', async () => {
    const status = await admin.client.get<KeyStatus>(`${ADMIN}/keys`);
    expect(status.status).toBe(200);
    expect(status.body!.data.masterKeys.map((key) => [key.purpose, key.name])).toEqual([
      ['USER_TYPE_ADMIN', 'scanova-utk-admin'],
      ['USER_TYPE_MEMBER', 'scanova-utk-member'],
      ['SYSTEM', 'scanova-sys'],
      ['JWT_SIGNING', 'scanova-jwt'],
    ]);
    expect(status.body!.data.userKeys.active).toBeGreaterThan(0);
    expect(status.raw.toString()).not.toMatch(/vault:v\d/u);
    expect((await admin.client.post(`${ADMIN}/keys/rotations`, { scope: 'USER_KEY' })).status).toBe(
      400,
    );
    expect(
      (
        await admin.client.post(`${ADMIN}/keys/rotations`, {
          scope: 'USER_KEY',
          userId: randomUUID(),
        })
      ).status,
    ).toBe(404);
  });

  it('rotates a user key: documents stay readable, the old version stops decrypting, the main database is untouched', async () => {
    const member = await createMember(api);
    const documents = [
      await createDocument(member),
      await createDocument(member),
      await createDocument(member, { file: null }),
    ];
    const oldKey = await keysDb().userKey.findFirstOrThrow({
      where: { userId: member.id, version: 1 },
    });
    const oldDataKeys = await keysDb().dataKey.findMany({ where: { userId: member.id } });

    const requested = await admin.client.post<RotationRun>(`${ADMIN}/keys/rotations`, {
      scope: 'USER_KEY',
      userId: member.id,
    });
    expect(requested.status).toBe(201);
    expect(requested.body!.data).toMatchObject({
      scope: 'USER_KEY',
      target: member.id,
      state: 'PENDING',
      trigger: 'MANUAL',
      fromVersion: 1,
      toVersion: 2,
    });

    // The worker's part. It has no connection to the main database at all.
    const before = await mainDatabaseFingerprint();
    const progress = await workerKeyring().rotation.run(requested.body!.data.id);
    expect(progress.run).toMatchObject({ state: 'COMPLETED', processedItems: 5, totalItems: 5 });
    expect(await mainDatabaseFingerprint()).toEqual(before);

    // Every document is readable, from a process with empty caches.
    const fresh = await startApp();
    await expectReadable(member, documents, fresh.client.as(member.tokens));
    await fresh.close();

    // The old version holds no key material; data keys are bound to the new one.
    const versions = await keysDb().userKey.findMany({
      where: { userId: member.id },
      orderBy: { version: 'asc' },
    });
    expect(versions.map((key) => [key.version, key.state, key.wrappedKey === null])).toEqual([
      [1, 'DESTROYED', true],
      [2, 'ACTIVE', false],
    ]);
    const dataKeys = await keysDb().dataKey.findMany({ where: { userId: member.id } });
    expect(dataKeys).toHaveLength(5);
    expect(dataKeys.every((key) => key.userKeyVersion === 2)).toBe(true);
    for (const old of oldDataKeys) {
      const current = dataKeys.find((key) => key.id === old.id)!;
      expect(Buffer.from(current.wrappedKey).equals(Buffer.from(old.wrappedKey))).toBe(false);
    }
    expect(oldKey.wrappedKey).not.toBeNull();

    const listed = await admin.client.get<RotationRun[]>(`${ADMIN}/keys/rotations?limit=200`);
    expect(listed.body!.data.find((run) => run.id === requested.body!.data.id)).toMatchObject({
      state: 'COMPLETED',
      processedItems: 5,
    });
  });

  it('rotates the member master key: documents stay readable, the old version stops decrypting', async () => {
    const members = [await createMember(api), await createMember(api)];
    const documents = new Map<string, { id: string; file: Buffer | null }[]>();
    for (const member of members) {
      documents.set(member.id, [
        await createDocument(member),
        await createDocument(member, { file: null }),
      ]);
    }
    const oldRow = await keysDb().userKey.findFirstOrThrow({
      where: { userId: members[0]!.id, state: 'ACTIVE' },
    });

    const requested = await admin.client.post<RotationRun>(`${ADMIN}/keys/rotations`, {
      scope: 'USER_TYPE_MASTER',
      userType: 'MEMBER',
    });
    expect(requested.status).toBe(201);
    expect(requested.body!.data).toMatchObject({
      scope: 'USER_TYPE_MASTER',
      target: 'scanova-utk-member',
      state: 'PENDING',
    });

    const before = await mainDatabaseFingerprint();
    const progress = await workerKeyring().rotation.run(requested.body!.data.id);
    expect(progress.run.state).toBe('COMPLETED');
    expect(await mainDatabaseFingerprint()).toEqual(before);
    const newVersion = progress.run.toVersion!;
    expect(newVersion).toBeGreaterThan(oldRow.masterKeyVersion);

    const remaining = await keysDb().userKey.count({
      where: {
        masterKeyName: 'scanova-utk-member',
        state: { not: 'DESTROYED' },
        masterKeyVersion: { lt: newVersion },
      },
    });
    expect(remaining).toBe(0);
    const info = await vault('api').getKeyInfo('scanova-utk-member');
    expect(info.minDecryptionVersion).toBe(newVersion);
    // The vault refuses what the old key version wrapped.
    expect(ciphertextKeyVersion(oldRow.wrappedKey!)).toBeLessThan(newVersion);
    await expect(
      vault('api').decrypt('scanova-utk-member', oldRow.wrappedKey!, members[0]!.id),
    ).rejects.toMatchObject({ code: 'REJECTED' });

    // Every document of every member is readable from a process with empty caches.
    const fresh = await startApp();
    for (const member of members) {
      await expectReadable(member, documents.get(member.id)!, fresh.client.as(member.tokens));
    }
    await fresh.close();
  });
});

describe('deleting a user', () => {
  it('crypto-shreds at once: keys destroyed, nothing recoverable, account gone', async () => {
    const member = await createMember(api);
    const documents = [
      await createDocument(member, { clientOcrText: 'to be shredded' }),
      await createDocument(member),
    ];
    const objects = await mainDb().fileObject.findMany({
      where: { document: { user: { publicId: member.id } } },
    });
    expect(objects).toHaveLength(2);

    const deleted = await admin.client.delete(`${ADMIN}/users/${member.id}`);
    expect(deleted.status).toBe(200);
    expect(deleted.body?.data).toEqual({ success: true });

    // Key database: every version of the user key is overwritten, every data key is gone.
    const userKeys = await keysDb().userKey.findMany({ where: { userId: member.id } });
    expect(userKeys.length).toBeGreaterThan(0);
    expect(userKeys.every((key) => key.state === 'DESTROYED' && key.wrappedKey === null)).toBe(
      true,
    );
    expect(await keysDb().dataKey.count({ where: { userId: member.id } })).toBe(0);

    // The ciphertext may still be there until the purge runs, but nothing can open it:
    // not this API, not a fresh process, not the worker.
    for (const object of objects) {
      expect(await bucket().exists(object.objectKey)).toBe(true);
    }
    for (const keyring of [workerKeyring()]) {
      for (const document of documents) {
        await expect(
          keyring.useDocumentKey(member.id, document.id, () => 'opened'),
        ).rejects.toMatchObject({ code: 'KEY_NOT_FOUND' });
      }
      await expect(keyring.useProfileKey(member.id, () => 'opened')).rejects.toMatchObject({
        code: 'KEY_NOT_FOUND',
      });
      await expect(keyring.createDocumentKey(member.id, randomUUID())).rejects.toMatchObject({
        code: 'KEY_DESTROYED',
      });
    }

    // The account is unusable in every way.
    expect((await member.client.get('/api/v1/me')).status).toBe(401);
    expect(
      (await api.client.post('/api/v1/auth/refresh', { refreshToken: member.tokens.refreshToken }))
        .status,
    ).toBe(401);
    expect(
      (
        await api.client.post('/api/v1/auth/login', {
          email: member.email,
          password: member.password,
          client: 'mobile',
          device: DEVICE,
        })
      ).body?.error?.code,
    ).toBe('INVALID_CREDENTIALS');
    expect(
      (await admin.client.get<User[]>(`${ADMIN}/users?limit=200`)).body!.data.map(
        (user) => user.id,
      ),
    ).not.toContain(member.id);
    expect((await admin.client.delete(`${ADMIN}/users/${member.id}`)).status).toBe(404);

    // The purge of rows and objects is queued for the worker, by id only.
    const jobs = await mainDb().$queryRaw<
      { data: unknown }[]
    >`SELECT data FROM pgboss.job WHERE name = 'user.purge' AND data->>'userId' = ${member.id}`;
    expect(jobs).toEqual([{ data: { userId: member.id } }]);
    // The address is free again.
    const again = await admin.client.post(`${ADMIN}/users`, {
      email: member.email,
      name: 'Someone New',
      userType: 'MEMBER',
    });
    expect(again.status).toBe(201);
  });
});

describe('audit log', () => {
  it('records security events with encrypted payloads and lists them for admins', async () => {
    const member = await createMember(api);
    await api.client.post('/api/v1/auth/login', {
      email: member.email,
      password: 'wrong',
      client: 'mobile',
      device: DEVICE,
    });
    await admin.client.post(`${ADMIN}/users/${member.id}/revoke-sessions`);

    const events: AuditEvent[] = [];
    let cursor: string | null = null;
    for (
      let page = 0;
      page < 20 &&
      events.every(
        (event) => event.payload.targetUserId !== member.id || event.action !== 'USER_CREATED',
      );
      page += 1
    ) {
      const response: ApiResponse<AuditEvent[]> = await admin.client.get<AuditEvent[]>(
        `${ADMIN}/audit-events?limit=50${cursor === null ? '' : `&cursor=${cursor}`}`,
      );
      expect(response.status).toBe(200);
      events.push(...response.body!.data);
      cursor = response.body!.meta!.nextCursor;
      if (cursor === null) {
        break;
      }
    }
    const mine = events.filter(
      (event) => event.actorId === member.id || event.payload.targetUserId === member.id,
    );
    const actions = mine.map((event) => event.action);
    for (const action of [
      'USER_CREATED',
      'LOGIN_SUCCEEDED',
      'PASSWORD_CHANGED',
      'LOGIN_FAILED',
      'USER_SESSIONS_REVOKED',
    ]) {
      expect(actions).toContain(action);
    }
    expect(mine.find((event) => event.action === 'USER_SESSIONS_REVOKED')).toMatchObject({
      actorId: admin.id,
      payload: { targetUserId: member.id, via: 'web' },
    });
    // Newest first.
    const times = events.map((event) => Date.parse(event.createdAt));
    expect([...times].sort((a, b) => b - a)).toEqual(times);
    // No e-mail address in any payload, and ciphertext at rest.
    expect(JSON.stringify(events)).not.toContain(member.email);
    const row = await mainDb().auditEvent.findFirstOrThrow({
      where: { action: 'USER_SESSIONS_REVOKED', actor: { publicId: admin.id } },
      orderBy: { id: 'desc' },
    });
    expect(row.payloadEnc[0]).toBe(0x01);
    expect(Buffer.from(row.payloadEnc).includes(Buffer.from(member.id))).toBe(false);
  });
});

describe('operator CLI', () => {
  async function cli(...argv: string[]): Promise<{ code: number; out: string; err: string }> {
    const out: string[] = [];
    const err: string[] = [];
    const code = await runCli(
      argv,
      { out: (line) => out.push(line), err: (line) => err.push(line) },
      () => {
        const logger = new AppLogger('fatal', 'cli', () => undefined);
        return NestFactory.createApplicationContext(AppModule.register(testConfig(), { logger }), {
          logger,
        });
      },
    );
    return { code, out: out.join('\n'), err: err.join('\n') };
  }

  it('creates admins and users, lists, disables and resets passwords', async () => {
    const adminEmail = uniqueEmail('cli-admin');
    const created = await cli('admin:create', '--email', adminEmail, '--name', 'Operator Admin');
    expect(created.code).toBe(0);
    expect(created.out).toMatch(/Created ADMIN [0-9a-f-]{36}/u);
    const password = /Temporary password[^:]*: (\S+)/u.exec(created.out)?.[1];
    expect(password).toMatch(/^[A-HJ-NP-Za-km-z2-9]{20}$/u);
    const web = await api.client
      .withHeaders(SERVICE_HEADER)
      .post<Tokens>('/internal/web/v1/auth/login', { email: adminEmail, password });
    expect(web.body!.data.user).toMatchObject({ userType: 'ADMIN', mustChangePassword: true });

    const memberEmail = uniqueEmail('cli-member');
    const member = await cli('user:create', '--email', memberEmail, '--name', 'CLI Member');
    expect(member.out).toMatch(/Created MEMBER/u);
    const memberId = /Created MEMBER (\S+)/u.exec(member.out)![1]!;

    const list = await cli('user:list');
    expect(list.code).toBe(0);
    expect(list.out).toContain(memberEmail);
    expect(list.out).toContain(adminEmail);
    expect(list.out).toMatch(/\d+ user\(s\)/u);

    const reset = await cli('user:reset-password', '--email', memberEmail);
    expect(reset.code).toBe(0);
    const newPassword = /Temporary password[^:]*: (\S+)/u.exec(reset.out)![1]!;
    expect(
      (
        await api.client.post('/api/v1/auth/login', {
          email: memberEmail,
          password: newPassword,
          client: 'mobile',
          device: DEVICE,
        })
      ).status,
    ).toBe(200);

    expect((await cli('user:disable', '--id', memberId)).out).toBe(`Disabled ${memberId}`);
    expect(
      (
        await api.client.post('/api/v1/auth/login', {
          email: memberEmail,
          password: newPassword,
          client: 'mobile',
          device: DEVICE,
        })
      ).body?.error?.code,
    ).toBe('ACCOUNT_DISABLED');

    // Events from the CLI are audited without an actor.
    const audit = await mainDb().auditEvent.findFirstOrThrow({
      where: { action: 'USER_PASSWORD_RESET' },
      orderBy: { id: 'desc' },
    });
    expect(audit.actorId).toBeNull();
  });

  it('shows key status and requests rotations for the worker', async () => {
    const status = await cli('keys:status');
    expect(status.code).toBe(0);
    expect(status.out).toMatch(/USER_TYPE_MEMBER\s+scanova-utk-member\s+v\d+\s+min v\d+/u);
    expect(status.out).toMatch(/user keys: \d+ active/u);

    const member = await createMember(api);
    const rotate = await cli('keys:rotate', '--scope', 'user-key', '--id', member.id);
    expect(rotate.code).toBe(0);
    const runId = /Rotation (\S+) requested \(USER_KEY, PENDING\)/u.exec(rotate.out)?.[1];
    expect(runId).toBeDefined();
    expect((await workerKeyring().rotation.run(runId!)).run.state).toBe('COMPLETED');
  });

  it('explains wrong usage and never starts the application for it', async () => {
    expect((await cli()).code).toBe(2);
    expect((await cli('help')).out).toContain('keys:rotate');
    const unknown = await cli('user:explode');
    expect(unknown.code).toBe(2);
    expect(unknown.err).toContain('Unknown command');
    expect((await cli('user:create', '--name', 'No Mail')).err).toContain('--email is required');
    expect((await cli('user:create', '--email', 'not-an-email', '--name', 'X')).code).toBe(2);
    expect((await cli('user:disable', '--email', 'nobody@example.test')).err).toContain(
      'No such user',
    );
    expect((await cli('keys:rotate', '--scope', 'everything')).code).toBe(2);
    expect((await cli('user:list', '--bogus')).code).toBe(2);
    const duplicate = await cli('user:create', '--email', admin.email, '--name', 'Dup');
    expect(duplicate.code).toBe(1);
    expect(duplicate.err).toContain('ID_CONFLICT');
  });
});

describe('token-signing key rotation', () => {
  it('signs with the new version at once, honours old tokens until they have expired, then retires the old version', async () => {
    // On a stack that is reused, an earlier suite may have left a signing-key rotation
    // waiting. Finish it first, so this test starts from a settled key.
    const unfinished = await keysDb().keyRotationRun.findMany({
      where: {
        scope: 'JWT_SIGNING',
        target: state.openbao.keys.jwt,
        state: { in: ['PENDING', 'RUNNING'] },
      },
    });
    for (const leftover of unfinished) {
      for (const hours of [1, 2]) {
        await workerKeyring({ now: () => new Date(Date.now() + hours * 3_600_000) }).rotation.run(
          leftover.publicId,
        );
      }
    }
    const app = await startApp();
    const member = await createMember(app);
    const oldKid = JSON.parse(
      Buffer.from(member.tokens.accessToken.split('.')[0]!, 'base64url').toString(),
    ) as { kid: string };

    const requested = await admin.client.post<RotationRun>(`${ADMIN}/keys/rotations`, {
      scope: 'JWT_SIGNING',
    });
    expect(requested.status).toBe(201);
    let offsetMs = 0;
    const worker = workerKeyring({ now: () => new Date(Date.now() + offsetMs) });
    const first = await worker.rotation.run(requested.body!.data.id);
    expect(first.state).toBe('WAITING');

    // The API notices the new version when its key cache expires and signs with it from then on.
    app.advance(61_000);
    const login = await app.client.post<Tokens>('/api/v1/auth/login', {
      email: member.email,
      password: member.password,
      client: 'mobile',
      device: DEVICE,
    });
    const newKid = JSON.parse(
      Buffer.from(login.body!.data.accessToken.split('.')[0]!, 'base64url').toString(),
    ) as { kid: string };
    expect(Number(newKid.kid.slice(1))).toBe(Number(oldKid.kid.slice(1)) + 1);
    // Tokens from before the rotation still verify.
    expect((await member.client.get('/api/v1/me')).status).toBe(200);
    expect((await app.client.as(login.body!.data).get('/api/v1/me')).status).toBe(200);

    // Access-token lifetime (600 s) plus margin (120 s) later the worker retires the old version.
    offsetMs = 721_000;
    const done = await worker.rotation.run(requested.body!.data.id);
    expect(done.run.state).toBe('COMPLETED');
    const info = await vault('api').getKeyInfo('scanova-jwt');
    expect(info.minDecryptionVersion).toBe(done.run.toVersion);

    // A process that starts now does not know the retired public key at all.
    const later = await startApp();
    expect((await later.client.as(member.tokens).get('/api/v1/me')).body?.error?.code).toBe(
      'UNAUTHENTICATED',
    );
    const again = await later.client.post<Tokens>('/api/v1/auth/login', {
      email: member.email,
      password: member.password,
      client: 'mobile',
      device: DEVICE,
    });
    expect((await later.client.as(again.body!.data).get('/api/v1/me')).status).toBe(200);
    await later.close();
    await app.close();
  });
});
