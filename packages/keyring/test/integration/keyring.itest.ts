import { randomBytes, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { buildPostgresConfig } from '@scanova/config/postgres';
import type { KeyNamesConfig } from '@scanova/config/sections';
import { CryptoError } from '@scanova/crypto/errors';
import { deriveKeyWrapKey } from '@scanova/crypto/hierarchy';
import { unwrapDataKey } from '@scanova/crypto/key-wrap';
import { createKeystoreClient, type KeystoreClient } from '@scanova/keystore/client';
import { migrateKeystore } from '@scanova/keystore/migrate';
import { InMemoryKms } from '@scanova/kms/in-memory';
import { ciphertextKeyVersion, type Kms } from '@scanova/kms/kms';
import { OpenBaoKms } from '@scanova/kms/openbao';
import { readIntegrationState } from '@scanova/tooling/integration/state';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { KeyringError } from '../../src/errors.js';
import { Keyring } from '../../src/keyring.js';
import type { KeyringSettings } from '../../src/types.js';

const state = readIntegrationState();
const tls = { caCertPath: state.pki.caCertPath };
const keysDb = state.postgres.keys;
const appConfig = buildPostgresConfig({
  host: state.postgres.host,
  port: state.postgres.port,
  database: keysDb.database,
  ...keysDb.app,
  tls,
});
const migratorConfig = buildPostgresConfig({
  host: state.postgres.host,
  port: state.postgres.port,
  database: keysDb.database,
  ...keysDb.migrator,
  tls,
});

const ROTATION = { userTypeKeyDays: 90, userKeyDays: 180, jwtKeyDays: 30, rewrapBatchSize: 2 };
const DAY_MS = 86_400_000;

/** Direct view of the key database, for assertions only. */
let inspect: KeystoreClient;
const opened: Keyring[] = [];
const vaults: Kms[] = [];

beforeAll(async () => {
  await migrateKeystore({ migrator: migratorConfig, runtimeRole: keysDb.app.user });
  inspect = createKeystoreClient(appConfig);
});

afterAll(async () => {
  await Promise.all(opened.map((keyring) => keyring.close()));
  await Promise.all(vaults.map((vault) => vault.close()));
  await inspect.$disconnect();
});

interface World {
  readonly vault: InMemoryKms;
  readonly names: KeyNamesConfig;
  /** Keyring with the worker's vault policy. */
  readonly worker: Keyring;
  /** Keyring with the API's vault policy (cannot rotate vault keys). */
  readonly api: Keyring;
  readonly clock: { now: Date };
  /** A keyring with an empty cache, as another process would have. */
  fresh(policy?: 'api' | 'worker'): Keyring;
}

/** An isolated vault with uniquely named keys, so tests never see each other's rows. */
function world(overrides: Partial<KeyringSettings> = {}): World {
  const suffix = randomBytes(5).toString('hex');
  const clock = { now: new Date() };
  const names: KeyNamesConfig = {
    userTypeAdmin: `it-utk-admin-${suffix}`,
    userTypeMember: `it-utk-member-${suffix}`,
    system: `it-sys-${suffix}`,
    jwt: `it-jwt-${suffix}`,
  };
  const vault = new InMemoryKms({ now: () => clock.now });
  vault.createKey(names.userTypeAdmin, { derived: true });
  vault.createKey(names.userTypeMember, { derived: true });
  vault.createKey(names.system);
  vault.createKey(names.jwt, { type: 'ed25519' });
  const settings: KeyringSettings = {
    keys: names,
    rotation: ROTATION,
    accessTokenTtlSeconds: 600,
    masterKeySettleSeconds: 0,
    signingKeyRetireMarginSeconds: 120,
    now: () => clock.now,
    ...overrides,
  };
  const fresh = (policy: 'api' | 'worker' = 'worker'): Keyring => {
    const keyring = new Keyring({
      kms: policy === 'worker' ? vault : vault.withPolicy({ allowKeyManagement: false }),
      keystore: createKeystoreClient(appConfig),
      settings,
    });
    opened.push(keyring);
    return keyring;
  };
  return { vault, names, worker: fresh('worker'), api: fresh('api'), clock, fresh };
}

/** Copies of a user's data keys, to compare before and after a rotation. */
async function snapshot(
  keyring: Keyring,
  userId: string,
  documentIds: string[],
): Promise<Map<string, string>> {
  const keys = new Map<string, string>();
  keys.set('PROFILE', await keyring.useProfileKey(userId, (key) => key.toString('hex')));
  keys.set('INDEX', await keyring.useIndexKey(userId, (key) => key.toString('hex')));
  await keyring.useDocumentKeys(userId, documentIds, (documentKeys) => {
    for (const [documentId, key] of documentKeys) {
      keys.set(documentId, key.toString('hex'));
    }
  });
  return keys;
}

async function codeOf(action: Promise<unknown>): Promise<string> {
  try {
    await action;
  } catch (error) {
    return (error as { code?: string }).code ?? 'UNKNOWN';
  }
  return 'OK';
}

describe('package boundary', () => {
  it('does not depend on the main database', () => {
    const manifest = JSON.parse(
      readFileSync(resolve(import.meta.dirname, '../../package.json'), 'utf8'),
    ) as {
      dependencies: Record<string, string>;
      devDependencies: Record<string, string>;
    };
    expect(Object.keys(manifest.dependencies).sort()).toEqual([
      '@scanova/config',
      '@scanova/crypto',
      '@scanova/keystore',
      '@scanova/kms',
    ]);
    expect(Object.keys(manifest.devDependencies)).not.toContain('@scanova/database');
  });
});

describe('user keys and data keys', () => {
  it('provisions a user key with PROFILE and INDEX data keys, stored only wrapped', async () => {
    const { api, names } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');

    const userKeys = await inspect.userKey.findMany({ where: { userId } });
    expect(userKeys).toHaveLength(1);
    expect(userKeys[0]).toMatchObject({
      version: 1,
      state: 'ACTIVE',
      masterKeyName: names.userTypeMember,
      masterKeyVersion: 1,
    });
    expect(userKeys[0]!.wrappedKey).toMatch(/^vault:v1:/u);

    const dataKeys = await inspect.dataKey.findMany({
      where: { userId },
      orderBy: { scope: 'asc' },
    });
    expect(
      dataKeys.map((key) => [key.scope, key.refId, key.userKeyVersion, key.wrappedKey.length]),
    ).toEqual([
      ['PROFILE', userId, 1, 61],
      ['INDEX', userId, 1, 61],
    ]);
    expect(dataKeys.every((key) => key.wrappedKey[0] === 0x01)).toBe(true);

    const profile = await api.useProfileKey(userId, (key) => Buffer.from(key));
    const index = await api.useIndexKey(userId, (key) => Buffer.from(key));
    expect(profile).toHaveLength(32);
    expect(profile.equals(index)).toBe(false);
    // The same key every time, also from a process with an empty cache.
    expect((await api.useProfileKey(userId, (key) => Buffer.from(key))).equals(profile)).toBe(true);
    expect(
      await world()
        .worker.useProfileKey(userId, () => 'unreachable')
        .catch(() => 'other vault'),
    ).toBe('other vault');
    await expect(api.provisionUser(userId, 'MEMBER')).rejects.toMatchObject({
      code: 'KEY_ALREADY_EXISTS',
    });
  });

  it('wipes the buffer it handed out once the callback returns', async () => {
    const { api } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    let leaked: Buffer | null = null;
    await api.useProfileKey(userId, (key) => {
      leaked = key;
      expect(key.equals(Buffer.alloc(32))).toBe(false);
    });
    expect(leaked!.equals(Buffer.alloc(32))).toBe(true);
    // The cache still holds the real key: the next use works.
    await api.useProfileKey(userId, (key) => expect(key.equals(Buffer.alloc(32))).toBe(false));
  });

  it('uses the user type to pick the master key', async () => {
    const { api, names } = world();
    const admin = randomUUID();
    await api.provisionUser(admin, 'ADMIN');
    expect(
      (await inspect.userKey.findFirstOrThrow({ where: { userId: admin } })).masterKeyName,
    ).toBe(names.userTypeAdmin);
  });

  it('cannot unwrap a user key copied onto another user (vault context = user id)', async () => {
    const { api, fresh } = world();
    const alice = randomUUID();
    const mallory = randomUUID();
    await api.provisionUser(alice, 'MEMBER');
    await api.provisionUser(mallory, 'MEMBER');
    const aliceKey = await inspect.userKey.findFirstOrThrow({ where: { userId: alice } });
    await inspect.userKey.updateMany({
      where: { userId: mallory },
      data: { wrappedKey: aliceKey.wrappedKey },
    });
    expect(await codeOf(fresh().useProfileKey(mallory, () => undefined))).toBe('REJECTED');
  });

  it('cannot unwrap a data key copied onto another user or document', async () => {
    const { api, fresh } = world();
    const alice = randomUUID();
    const mallory = randomUUID();
    await api.provisionUser(alice, 'MEMBER');
    await api.provisionUser(mallory, 'MEMBER');
    const aliceProfile = await inspect.dataKey.findFirstOrThrow({
      where: { userId: alice, scope: 'PROFILE' },
    });
    await inspect.dataKey.updateMany({
      where: { userId: mallory, scope: 'PROFILE' },
      data: { wrappedKey: aliceProfile.wrappedKey },
    });
    await expect(fresh().useProfileKey(mallory, () => undefined)).rejects.toBeInstanceOf(
      CryptoError,
    );

    const [first, second] = [randomUUID(), randomUUID()];
    await api.createDocumentKey(alice, first);
    await api.createDocumentKey(alice, second);
    const firstRow = await inspect.dataKey.findFirstOrThrow({
      where: { userId: alice, refId: first },
    });
    await inspect.dataKey.updateMany({
      where: { userId: alice, refId: second },
      data: { wrappedKey: firstRow.wrappedKey },
    });
    await expect(api.useDocumentKey(alice, second, () => undefined)).rejects.toBeInstanceOf(
      CryptoError,
    );
  });

  it('creates a DOCUMENT key once, however often and concurrently it is asked for', async () => {
    const { api } = world();
    const userId = randomUUID();
    const documentId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');

    await Promise.all(Array.from({ length: 8 }, () => api.createDocumentKey(userId, documentId)));
    expect(await inspect.dataKey.count({ where: { userId, scope: 'DOCUMENT' } })).toBe(1);
    const first = await api.useDocumentKey(userId, documentId, (key) => key.toString('hex'));

    const before = await inspect.dataKey.findFirstOrThrow({ where: { userId, refId: documentId } });
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 20));
    await api.createDocumentKey(userId, documentId);
    const after = await inspect.dataKey.findFirstOrThrow({ where: { userId, refId: documentId } });
    expect(after.claimedAt.getTime()).toBeGreaterThan(before.claimedAt.getTime());
    expect(Buffer.from(after.wrappedKey).equals(Buffer.from(before.wrappedKey))).toBe(true);
    expect(await api.useDocumentKey(userId, documentId, (key) => key.toString('hex'))).toBe(first);
  });

  it('reads many DOCUMENT keys at once and destroys them one document at a time', async () => {
    const { api } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    const documentIds = [randomUUID(), randomUUID(), randomUUID()];
    for (const documentId of documentIds) {
      await api.createDocumentKey(userId, documentId);
    }
    const missing = randomUUID();
    const found = await api.useDocumentKeys(userId, [...documentIds, missing], (keys) =>
      [...keys.keys()].sort(),
    );
    expect(found).toEqual([...documentIds].sort());

    expect(await api.destroyDocumentKeys(userId, [documentIds[0]!])).toBe(1);
    expect(await api.destroyDocumentKeys(userId, [documentIds[0]!])).toBe(0);
    await expect(
      api.useDocumentKey(userId, documentIds[0]!, () => undefined),
    ).rejects.toMatchObject({ code: 'KEY_NOT_FOUND' });
    await expect(api.useDocumentKey(userId, documentIds[1]!, () => 'still there')).resolves.toBe(
      'still there',
    );
    // Another user cannot reach the key by naming the document.
    const other = randomUUID();
    await api.provisionUser(other, 'MEMBER');
    await expect(api.useDocumentKey(other, documentIds[1]!, () => undefined)).rejects.toMatchObject(
      { code: 'KEY_NOT_FOUND' },
    );
  });

  it('crypto-shreds a user: keys are destroyed by UPDATE and nothing can be unwrapped any more', async () => {
    const { api, fresh } = world();
    const userId = randomUUID();
    const documentId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    await api.createDocumentKey(userId, documentId);
    await api.useProfileKey(userId, () => undefined);

    await api.destroyUser(userId);
    await api.destroyUser(userId);

    const userKeys = await inspect.userKey.findMany({ where: { userId } });
    expect(userKeys).toHaveLength(1);
    expect(userKeys[0]).toMatchObject({ state: 'DESTROYED', wrappedKey: null });
    expect(userKeys[0]!.destroyedAt).not.toBeNull();
    expect(await inspect.dataKey.count({ where: { userId } })).toBe(0);

    // Also for the process that had the keys cached a moment ago.
    await expect(api.useProfileKey(userId, () => undefined)).rejects.toMatchObject({
      code: 'KEY_NOT_FOUND',
    });
    await expect(api.useDocumentKey(userId, documentId, () => undefined)).rejects.toMatchObject({
      code: 'KEY_NOT_FOUND',
    });
    await expect(api.createDocumentKey(userId, documentId)).rejects.toMatchObject({
      code: 'KEY_DESTROYED',
    });
    await expect(fresh().createDocumentKey(userId, documentId)).rejects.toMatchObject({
      code: 'KEY_DESTROYED',
    });
    await expect(
      api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' }),
    ).rejects.toMatchObject({ code: 'KEY_DESTROYED' });
  });
});

describe('user-key rotation', () => {
  it('re-wraps every data key in batches, keeps the data keys, and destroys the old version', async () => {
    const { api, worker, fresh, vault, names } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    const documentIds = Array.from({ length: 5 }, () => randomUUID());
    for (const documentId of documentIds) {
      await api.createDocumentKey(userId, documentId);
    }
    const before = await snapshot(api, userId, documentIds);
    const oldRows = await inspect.dataKey.findMany({ where: { userId } });
    const oldUserKey = await inspect.userKey.findFirstOrThrow({ where: { userId, version: 1 } });

    // The API starts it (e.g. on a password reset): the new version is active at once.
    const requested = await api.rotation.request({
      scope: 'USER_KEY',
      userId,
      trigger: 'PASSWORD_RESET',
    });
    expect(requested).toMatchObject({
      scope: 'USER_KEY',
      target: userId,
      state: 'PENDING',
      fromVersion: 1,
      toVersion: 2,
      totalItems: 7,
    });
    expect(
      await api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' }),
    ).toMatchObject({ id: requested.id });
    expect(
      (await inspect.userKey.findMany({ where: { userId }, orderBy: { version: 'asc' } })).map(
        (key) => key.state,
      ),
    ).toEqual(['RETIRED', 'ACTIVE']);

    // Until the worker has run, everything is still readable under the retired version,
    // and anything new is already wrapped under the new one.
    expect(await snapshot(fresh('api'), userId, documentIds)).toEqual(before);
    const lateDocument = randomUUID();
    await api.createDocumentKey(userId, lateDocument);
    expect(
      (await inspect.dataKey.findFirstOrThrow({ where: { userId, refId: lateDocument } }))
        .userKeyVersion,
    ).toBe(2);

    const progress = await worker.rotation.run(requested.id);
    expect(progress.state).toBe('COMPLETED');
    expect(progress.run).toMatchObject({ state: 'COMPLETED', processedItems: 7, totalItems: 7 });
    expect(progress.run.finishedAt).not.toBeNull();

    // Every data key is now under version 2 and is still the same key.
    const rows = await inspect.dataKey.findMany({ where: { userId } });
    expect(rows).toHaveLength(8);
    expect(rows.every((row) => row.userKeyVersion === 2)).toBe(true);
    expect(await snapshot(fresh('api'), userId, documentIds)).toEqual(before);
    expect(await snapshot(api, userId, documentIds)).toEqual(before);

    // The old version holds no key material and can no longer unwrap anything.
    const versions = await inspect.userKey.findMany({
      where: { userId },
      orderBy: { version: 'asc' },
    });
    expect(versions.map((key) => [key.version, key.state, key.wrappedKey === null])).toEqual([
      [1, 'DESTROYED', true],
      [2, 'ACTIVE', false],
    ]);
    // Even someone who kept the old wrapped data keys AND still had vault access to the
    // new user key cannot open them: they are bound to the destroyed version.
    const newUserKey = await vault.decrypt(names.userTypeMember, versions[1]!.wrappedKey!, userId);
    const newWrapKey = deriveKeyWrapKey(newUserKey);
    for (const old of oldRows) {
      expect(() =>
        unwrapDataKey(newWrapKey, old.wrappedKey, {
          scope: old.scope,
          userId,
          refId: old.refId,
          userKeyVersion: 1,
        }),
      ).toThrow(CryptoError);
      expect(() =>
        unwrapDataKey(newWrapKey, old.wrappedKey, {
          scope: old.scope,
          userId,
          refId: old.refId,
          userKeyVersion: 2,
        }),
      ).toThrow(CryptoError);
    }
    // The only thing that could: the old user key, whose last copy was just overwritten.
    expect(oldUserKey.wrappedKey).toMatch(/^vault:v1:/u);
    expect(
      (await inspect.userKey.findFirstOrThrow({ where: { userId, version: 1 } })).wrappedKey,
    ).toBeNull();

    // Running a finished run again changes nothing.
    expect((await worker.rotation.run(requested.id)).run.processedItems).toBe(7);
  });

  it('resumes after a crash in the middle and tolerates two workers on the same run', async () => {
    const { api, worker, fresh, vault, names } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    const documentIds = Array.from({ length: 7 }, () => randomUUID());
    for (const documentId of documentIds) {
      await api.createDocumentKey(userId, documentId);
    }
    const before = await snapshot(api, userId, documentIds);
    const run = await api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' });

    // A worker whose database connection dies during its second batch.
    let transactions = 0;
    const real = createKeystoreClient(appConfig);
    const flaky = new Proxy(real, {
      get(target, property, receiver): unknown {
        if (property === '$transaction') {
          return (...args: unknown[]): unknown => {
            transactions += 1;
            if (transactions === 2) {
              return Promise.reject(new Error('connection lost'));
            }
            return (target.$transaction as (...rest: unknown[]) => unknown)(...args);
          };
        }
        return Reflect.get(target, property, receiver) as unknown;
      },
    });
    const crashing = new Keyring({
      kms: vault,
      keystore: flaky,
      settings: { keys: names, rotation: ROTATION, accessTokenTtlSeconds: 600 },
    });
    opened.push(crashing);
    await expect(crashing.rotation.run(run.id)).rejects.toThrow('connection lost');

    const halfway = await inspect.dataKey.findMany({ where: { userId } });
    expect(halfway.filter((row) => row.userKeyVersion === 2)).toHaveLength(2);
    expect((await api.rotation.get(run.id)).state).toBe('RUNNING');
    // Mid-rotation, with keys under both versions, everything is readable.
    expect(await snapshot(fresh('api'), userId, documentIds)).toEqual(before);

    // Two workers pick the run up at the same time.
    const results = await Promise.all([worker.rotation.run(run.id), fresh().rotation.run(run.id)]);
    expect(results.map((result) => result.state)).toEqual(['COMPLETED', 'COMPLETED']);
    const done = await inspect.dataKey.findMany({ where: { userId } });
    expect(done.every((row) => row.userKeyVersion === 2)).toBe(true);
    expect(await snapshot(fresh('api'), userId, documentIds)).toEqual(before);
    expect((await api.rotation.get(run.id)).processedItems).toBe(9);
    expect((await inspect.userKey.findFirstOrThrow({ where: { userId, version: 1 } })).state).toBe(
      'DESTROYED',
    );
  });

  it('never wraps a new data key under a version that is being destroyed', async () => {
    const { api, worker } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    // Writers and three rotations race each other.
    const created: string[] = [];
    const writers = Array.from({ length: 30 }, async () => {
      const documentId = randomUUID();
      await api.createDocumentKey(userId, documentId);
      created.push(documentId);
    });
    const rotations = (async (): Promise<void> => {
      for (let round = 0; round < 3; round += 1) {
        const run = await api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' });
        await worker.rotation.run(run.id);
      }
    })();
    await Promise.all([...writers, rotations]);

    const active = await inspect.userKey.findFirstOrThrow({ where: { userId, state: 'ACTIVE' } });
    const destroyed = await inspect.userKey.findMany({ where: { userId, state: 'DESTROYED' } });
    const rows = await inspect.dataKey.findMany({ where: { userId } });
    expect(rows).toHaveLength(32);
    // No data key points at a destroyed version: data without a key must never exist.
    for (const row of rows) {
      expect(destroyed.map((key) => key.version)).not.toContain(row.userKeyVersion);
    }
    const pending = rows.filter((row) => row.userKeyVersion !== active.version);
    if (pending.length > 0) {
      const run = await api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' });
      await worker.rotation.run(run.id);
    }
    const readable = await api.useDocumentKeys(userId, created, (keys) => keys.size);
    expect(readable).toBe(30);
  });

  it('never lets a stale failed run destroy the version a newer rotation moved to', async () => {
    const { api, worker, fresh } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    const documentId = randomUUID();
    await api.createDocumentKey(userId, documentId);
    // R1 fails before doing anything; R2 (for example a password reset) supersedes it.
    const r1 = await api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' });
    await worker.rotation.fail(r1.id, 'TEST_FAILURE');
    const r2 = await api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' });
    expect(r2.id).not.toBe(r1.id);
    // A leftover execution of R1 must change nothing.
    await worker.rotation.run(r1.id);
    expect(await inspect.userKey.count({ where: { userId, state: 'DESTROYED' } })).toBe(0);
    expect(await worker.rotation.run(r2.id)).toMatchObject({ state: 'COMPLETED' });
    const active = await inspect.userKey.findFirstOrThrow({ where: { userId, state: 'ACTIVE' } });
    const rows = await inspect.dataKey.findMany({ where: { userId } });
    expect(rows.every((row) => row.userKeyVersion === active.version)).toBe(true);
    expect(await fresh().useDocumentKeys(userId, [documentId], (keys) => keys.size)).toBe(1);
  });

  it('lets the sweep finish a user-key rotation that failed', async () => {
    const { api, worker, fresh } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    const documentId = randomUUID();
    await api.createDocumentKey(userId, documentId);
    const failed = await api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' });
    await worker.rotation.fail(failed.id, 'TEST_FAILURE');
    expect(await inspect.userKey.count({ where: { userId, state: 'RETIRED' } })).toBe(1);

    const { runIds } = await worker.rotation.sweep();
    const resumed = await inspect.keyRotationRun.findMany({
      where: { scope: 'USER_KEY', target: userId, state: 'PENDING' },
    });
    expect(resumed).toHaveLength(1);
    expect(runIds).toContain(resumed[0]?.publicId);
    await worker.rotation.run(resumed[0]?.publicId ?? '');
    expect(await inspect.userKey.count({ where: { userId, state: 'RETIRED' } })).toBe(0);
    expect(await fresh().useDocumentKeys(userId, [documentId], (keys) => keys.size)).toBe(1);
  });

  it('gives up cleanly when the user is deleted while a rotation is pending', async () => {
    const { api, worker } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    const run = await api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' });
    await api.destroyUser(userId);
    expect(await api.rotation.get(run.id)).toMatchObject({
      state: 'FAILED',
      errorCode: 'USER_KEYS_DESTROYED',
    });
    expect(
      (await inspect.userKey.findMany({ where: { userId } })).every(
        (key) => key.wrappedKey === null,
      ),
    ).toBe(true);
    await expect(worker.rotation.run(randomUUID())).rejects.toMatchObject({
      code: 'RUN_NOT_FOUND',
    });
  });
});

describe('user-type change', () => {
  it('re-wraps the user key under the other master key and rotates it', async () => {
    const { api, worker, fresh, names } = world();
    const userId = randomUUID();
    const documentId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    await api.createDocumentKey(userId, documentId);
    const before = await snapshot(api, userId, [documentId]);

    const run = await api.changeUserType(userId, 'ADMIN', randomUUID());
    expect(run).toMatchObject({
      scope: 'USER_KEY',
      trigger: 'USER_TYPE_CHANGE',
      fromVersion: 1,
      toVersion: 2,
    });
    const rows = await inspect.userKey.findMany({ where: { userId }, orderBy: { version: 'asc' } });
    expect(rows.map((row) => row.masterKeyName)).toEqual([
      names.userTypeAdmin,
      names.userTypeAdmin,
    ]);
    // Readable from a cold cache both before and after the re-wrap of the data keys.
    expect(await snapshot(fresh('api'), userId, [documentId])).toEqual(before);
    await worker.rotation.run(run.id);
    expect(await snapshot(fresh('api'), userId, [documentId])).toEqual(before);
    expect((await inspect.userKey.findFirstOrThrow({ where: { userId, version: 1 } })).state).toBe(
      'DESTROYED',
    );
  });
});

describe('user-type master key rotation', () => {
  it('re-wraps every user key in the vault, then stops the old version from decrypting', async () => {
    const { api, worker, fresh, vault, names } = world();
    const members = Array.from({ length: 5 }, () => randomUUID());
    const admin = randomUUID();
    for (const userId of members) {
      await api.provisionUser(userId, 'MEMBER');
    }
    await api.provisionUser(admin, 'ADMIN');
    const before = await Promise.all(members.map((userId) => snapshot(api, userId, [])));
    const oldWrapped = (await inspect.userKey.findFirstOrThrow({ where: { userId: members[0]! } }))
      .wrappedKey!;

    // The API may ask for it but has no permission to rotate a vault key itself.
    const requested = await api.rotation.request({
      scope: 'USER_TYPE_MASTER',
      userType: 'MEMBER',
      trigger: 'MANUAL',
      requestedBy: admin,
    });
    expect(requested).toMatchObject({
      state: 'PENDING',
      target: names.userTypeMember,
      requestedBy: admin,
    });
    expect(
      await api.rotation.request({
        scope: 'USER_TYPE_MASTER',
        userType: 'MEMBER',
        trigger: 'MANUAL',
      }),
    ).toMatchObject({ id: requested.id });
    expect(await codeOf(api.rotation.run(requested.id))).toBe('PERMISSION_DENIED');

    const progress = await worker.rotation.run(requested.id);
    expect(progress.state).toBe('COMPLETED');
    expect(progress.run).toMatchObject({
      fromVersion: 1,
      toVersion: 2,
      totalItems: 5,
      processedItems: 5,
    });

    const rows = await inspect.userKey.findMany({ where: { userId: { in: members } } });
    expect(
      rows.every(
        (row) => row.masterKeyVersion === 2 && ciphertextKeyVersion(row.wrappedKey!) === 2,
      ),
    ).toBe(true);
    // The admin key is a different master key and is untouched.
    expect(
      (await inspect.userKey.findFirstOrThrow({ where: { userId: admin } })).masterKeyVersion,
    ).toBe(1);
    expect((await vault.getKeyInfo(names.userTypeAdmin)).latestVersion).toBe(1);

    // Everything is still readable from a cold cache…
    const after = await Promise.all(members.map((userId) => snapshot(fresh('api'), userId, [])));
    expect(after).toEqual(before);
    // …and the old key version no longer decrypts.
    expect((await vault.getKeyInfo(names.userTypeMember)).minDecryptionVersion).toBe(2);
    expect(await codeOf(vault.decrypt(names.userTypeMember, oldWrapped, members[0]))).toBe(
      'REJECTED',
    );
    // New users are wrapped under the new version.
    const late = randomUUID();
    await api.provisionUser(late, 'MEMBER');
    expect(
      (await inspect.userKey.findFirstOrThrow({ where: { userId: late } })).masterKeyVersion,
    ).toBe(2);
  });

  it('waits for the settle time before retiring the old version', async () => {
    const { api, worker, vault, names, clock } = world({ masterKeySettleSeconds: 60 });
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    // A user key that was wrapped under the old version just before the rotation
    // and whose row only reaches the database afterwards.
    const straggler = randomUUID();
    const stragglerKey = randomBytes(32);
    const stragglerWrapped = await vault.encrypt(names.userTypeMember, stragglerKey, straggler);
    expect(ciphertextKeyVersion(stragglerWrapped)).toBe(1);

    const run = await api.rotation.request({
      scope: 'USER_TYPE_MASTER',
      userType: 'MEMBER',
      trigger: 'MANUAL',
    });
    const first = await worker.rotation.run(run.id);
    expect(first.state).toBe('WAITING');
    expect(first.state === 'WAITING' && first.retryAfterMs).toBe(60_000);
    // Re-wrapped already, but the old version is still allowed to decrypt.
    expect((await inspect.userKey.findFirstOrThrow({ where: { userId } })).masterKeyVersion).toBe(
      2,
    );
    expect((await vault.getKeyInfo(names.userTypeMember)).minDecryptionVersion).toBe(1);

    await inspect.userKey.create({
      data: {
        userId: straggler,
        version: 1,
        wrappedKey: stragglerWrapped,
        masterKeyName: names.userTypeMember,
        masterKeyVersion: 1,
      },
    });

    clock.now = new Date(clock.now.getTime() + 59_000);
    expect((await worker.rotation.run(run.id)).state).toBe('WAITING');
    clock.now = new Date(clock.now.getTime() + 2_000);
    const second = await worker.rotation.run(run.id);
    expect(second.state).toBe('COMPLETED');
    expect((await vault.getKeyInfo(names.userTypeMember)).minDecryptionVersion).toBe(2);
    expect((await vault.getKeyInfo(names.userTypeMember)).latestVersion).toBe(2);
    // The late arrival was re-wrapped before the old version was retired, so it survives.
    const landed = await inspect.userKey.findFirstOrThrow({ where: { userId: straggler } });
    expect(landed.masterKeyVersion).toBe(2);
    expect(
      (await vault.decrypt(names.userTypeMember, landed.wrappedKey!, straggler)).equals(
        stragglerKey,
      ),
    ).toBe(true);
  });

  it('does not rotate twice when it resumes after the vault already rotated', async () => {
    const { api, worker, vault, names } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    const run = await api.rotation.request({
      scope: 'USER_TYPE_MASTER',
      userType: 'MEMBER',
      trigger: 'MANUAL',
    });
    // As if a worker recorded its intent, rotated, and died before writing the new version down.
    await inspect.keyRotationRun.update({
      where: { publicId: run.id },
      data: { fromVersion: 1, state: 'RUNNING' },
    });
    await vault.rotateKey(names.userTypeMember);

    const progress = await worker.rotation.run(run.id);
    expect(progress.run).toMatchObject({ state: 'COMPLETED', fromVersion: 1, toVersion: 2 });
    expect((await vault.getKeyInfo(names.userTypeMember)).latestVersion).toBe(2);
  });

  it('picks up a version the vault rotated on its own schedule', async () => {
    const { api, worker, vault, names } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    await vault.rotateKey(names.userTypeMember);

    const { runIds } = await worker.rotation.sweep();
    const runs = await Promise.all(runIds.map((id) => api.rotation.get(id)));
    const pickedUp = runs.find((run) => run.target === names.userTypeMember);
    expect(pickedUp).toMatchObject({
      scope: 'USER_TYPE_MASTER',
      trigger: 'SCHEDULED',
      toVersion: 2,
    });

    await worker.rotation.run(pickedUp!.id);
    expect((await vault.getKeyInfo(names.userTypeMember)).latestVersion).toBe(2);
    expect((await vault.getKeyInfo(names.userTypeMember)).minDecryptionVersion).toBe(2);
    expect((await inspect.userKey.findFirstOrThrow({ where: { userId } })).masterKeyVersion).toBe(
      2,
    );
  });
});

describe('token-signing key rotation', () => {
  it('signs with the new version at once and retires the old one after the token lifetime', async () => {
    const { api, worker, vault, names, clock } = world();
    const run = await api.rotation.request({ scope: 'JWT_SIGNING', trigger: 'MANUAL' });

    const first = await worker.rotation.run(run.id);
    expect(first.state).toBe('WAITING');
    // Access tokens live 600 s; the margin for cached signers and clock skew is 120 s.
    expect(first.state === 'WAITING' && first.retryAfterMs).toBe(720_000);
    const during = await vault.getKeyInfo(names.jwt);
    expect(during.latestVersion).toBe(2);
    expect(during.minDecryptionVersion).toBe(1);
    expect(during.versions.map((version) => version.version)).toEqual([1, 2]);
    expect((await vault.sign(names.jwt, Buffer.from('x'))).keyVersion).toBe(2);

    clock.now = new Date(clock.now.getTime() + 719_000);
    expect((await worker.rotation.run(run.id)).state).toBe('WAITING');
    clock.now = new Date(clock.now.getTime() + 1_000);
    const done = await worker.rotation.run(run.id);
    expect(done.run).toMatchObject({ state: 'COMPLETED', fromVersion: 1, toVersion: 2 });
    const after = await vault.getKeyInfo(names.jwt);
    expect(after.minDecryptionVersion).toBe(2);
    expect(after.versions.map((version) => version.version)).toEqual([2]);
  });
});

describe('the sweep asks the policy', () => {
  it('starts rotations for keys that are due and leaves the rest alone', async () => {
    const { api, worker, names, clock } = world();
    const young = randomUUID();
    const old = randomUUID();
    await api.provisionUser(young, 'MEMBER');
    await api.provisionUser(old, 'MEMBER');
    // 181 days old: past KEY_ROTATION_USER_KEY_DAYS (180).
    await inspect.userKey.updateMany({
      where: { userId: old },
      data: { createdAt: new Date(clock.now.getTime() - 181 * DAY_MS) },
    });

    const first = await worker.rotation.sweep();
    const runs = await Promise.all(first.runIds.map((id) => api.rotation.get(id)));
    expect(runs.find((run) => run.target === old)).toMatchObject({
      scope: 'USER_KEY',
      trigger: 'SCHEDULED',
      state: 'PENDING',
    });
    expect(runs.find((run) => run.target === young)).toBeUndefined();
    // Vault keys were created "now": not due.
    expect(runs.find((run) => run.target === names.userTypeMember)).toBeUndefined();
    expect(runs.find((run) => run.target === names.jwt)).toBeUndefined();

    // 91 days later the master keys (90) and the signing key (30) are due.
    clock.now = new Date(clock.now.getTime() + 91 * DAY_MS);
    const second = await worker.rotation.sweep();
    const later = await Promise.all(second.runIds.map((id) => api.rotation.get(id)));
    for (const target of [names.userTypeMember, names.userTypeAdmin, names.jwt]) {
      expect(later.find((run) => run.target === target)).toMatchObject({
        trigger: 'SCHEDULED',
        state: 'PENDING',
        toVersion: null,
      });
    }
    // Sweeping again does not pile up duplicates.
    const third = await worker.rotation.sweep();
    const again = await Promise.all(third.runIds.map((id) => api.rotation.get(id)));
    expect(again.filter((run) => run.target === names.userTypeMember)).toHaveLength(1);
    expect(again.filter((run) => run.target === old)).toHaveLength(1);
  });
});

describe('status and listing', () => {
  it('reports versions, ages and pending re-wraps without any key material', async () => {
    const { api, vault, names, clock } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    await vault.rotateKey(names.userTypeMember);
    clock.now = new Date(clock.now.getTime() + 100 * DAY_MS);

    const status = await api.status();
    const member = status.masterKeys.find((key) => key.purpose === 'USER_TYPE_MEMBER');
    expect(member).toMatchObject({
      name: names.userTypeMember,
      latestVersion: 2,
      minDecryptionVersion: 1,
      ageDays: 100,
      rotationDue: true,
      pendingRewrap: 1,
    });
    expect(status.masterKeys.map((key) => key.purpose)).toEqual([
      'USER_TYPE_ADMIN',
      'USER_TYPE_MEMBER',
      'SYSTEM',
      'JWT_SIGNING',
    ]);
    expect(status.masterKeys.find((key) => key.purpose === 'SYSTEM')?.rotationDue).toBe(false);
    expect(status.masterKeys.find((key) => key.purpose === 'JWT_SIGNING')?.rotationDue).toBe(true);
    expect(status.userKeys.active).toBeGreaterThanOrEqual(1);
    expect(status.dataKeys).toBeGreaterThanOrEqual(2);
    expect(JSON.stringify(status)).not.toMatch(/vault:v/u);
  });

  it('lists rotation runs newest first with a cursor', async () => {
    const { api, clock } = world();
    const users = [randomUUID(), randomUUID(), randomUUID()];
    const ids: string[] = [];
    for (const userId of users) {
      await api.provisionUser(userId, 'MEMBER');
      ids.push((await api.rotation.request({ scope: 'USER_KEY', userId, trigger: 'MANUAL' })).id);
      clock.now = new Date(clock.now.getTime() + 1000);
    }
    // The table is shared with other tests, so walk the pages and look at our own runs.
    const seen: string[] = [];
    let cursor: string | undefined;
    for (let page = 0; page < 500; page += 1) {
      const result = await api.rotation.list({ limit: 2, cursor });
      expect(result.runs.length).toBeLessThanOrEqual(2);
      seen.push(...result.runs.map((run) => run.id));
      if (result.nextCursor === null) {
        break;
      }
      cursor = result.nextCursor;
    }
    expect(new Set(seen).size).toBe(seen.length);
    expect(seen.filter((id) => ids.includes(id))).toEqual([ids[2], ids[1], ids[0]]);
    await expect(api.rotation.list({ limit: 1, cursor: 'garbage' })).rejects.toBeInstanceOf(
      KeyringError,
    );
  });
});

describe('garbage collection of keys without data', () => {
  it('lists unclaimed DOCUMENT keys and deletes only those still unclaimed', async () => {
    const { api } = world();
    const userId = randomUUID();
    await api.provisionUser(userId, 'MEMBER');
    const [orphan, reclaimed, recent] = [randomUUID(), randomUUID(), randomUUID()];
    for (const documentId of [orphan, reclaimed, recent]) {
      await api.createDocumentKey(userId, documentId);
    }
    const longAgo = new Date(Date.now() - 3 * DAY_MS);
    await inspect.dataKey.updateMany({
      where: { userId, refId: { in: [orphan, reclaimed] } },
      data: { claimedAt: longAgo },
    });

    const cutoff = new Date(Date.now() - DAY_MS);
    const listed = (
      await api.listUnclaimedDocumentKeys({ claimedBefore: cutoff, limit: 1000 })
    ).filter((ref) => ref.userId === userId);
    expect(listed.map((ref) => ref.documentId).sort()).toEqual([orphan, reclaimed].sort());

    // A writer retries the upload of one of them just before the collector deletes.
    await api.createDocumentKey(userId, reclaimed);
    expect(await api.deleteUnclaimedDocumentKeys(listed, cutoff)).toBe(1);
    await expect(api.useDocumentKey(userId, orphan, () => undefined)).rejects.toMatchObject({
      code: 'KEY_NOT_FOUND',
    });
    await expect(api.useDocumentKey(userId, reclaimed, () => 'kept')).resolves.toBe('kept');
    await expect(api.useDocumentKey(userId, recent, () => 'kept')).resolves.toBe('kept');

    const owners = await api.listKeyOwners({
      createdBefore: new Date(Date.now() + 1000),
      limit: 10_000,
    });
    expect(owners).toContain(userId);
    expect(
      await api.listKeyOwners({
        createdBefore: longAgo,
        afterUserId: 'ffffffff-ffff-4fff-bfff-ffffffffffff',
        limit: 10,
      }),
    ).toEqual([]);
  });
});

describe('against a real OpenBao', () => {
  function vaultClient(role: 'api' | 'worker'): OpenBaoKms {
    const client = new OpenBaoKms({
      address: state.openbao.address,
      transitMount: state.openbao.transitMount,
      caCertPath: null,
      roleId: state.openbao[role].roleId,
      secretId: state.openbao[role].secretId,
    });
    vaults.push(client);
    return client;
  }

  function realKeyring(role: 'api' | 'worker'): Keyring {
    const keyring = new Keyring({
      kms: vaultClient(role),
      keystore: createKeystoreClient(appConfig),
      settings: {
        keys: state.openbao.keys,
        rotation: ROTATION,
        accessTokenTtlSeconds: 600,
        masterKeySettleSeconds: 0,
      },
    });
    opened.push(keyring);
    return keyring;
  }

  it('creates system keys once and shares them between processes', async () => {
    const [a, b] = [realKeyring('api'), realKeyring('worker')];
    const [first, second] = await Promise.all([
      a.useSystemKey('EMAIL_INDEX', (key) => key.toString('hex')),
      b.useSystemKey('EMAIL_INDEX', (key) => key.toString('hex')),
    ]);
    expect(first).toBe(second);
    expect(first).toHaveLength(64);
    const audit = await a.useSystemKey('AUDIT', (key) => key.toString('hex'));
    expect(audit).not.toBe(first);
    expect(await realKeyring('api').useSystemKey('AUDIT', (key) => key.toString('hex'))).toBe(
      audit,
    );
    const rows = await inspect.systemKey.findMany({
      where: { masterKeyName: state.openbao.keys.system },
    });
    expect(rows.map((row) => row.purpose).sort()).toEqual(['AUDIT', 'EMAIL_INDEX']);
    expect(rows.every((row) => row.wrappedKey.startsWith('vault:v'))).toBe(true);
    await a.health();
  });

  it('rotates the member master key in the vault: data stays readable, the old version stops decrypting', async () => {
    const api = realKeyring('api');
    const worker = realKeyring('worker');
    const users = [randomUUID(), randomUUID(), randomUUID()];
    const documents = new Map<string, string>();
    for (const userId of users) {
      await api.provisionUser(userId, 'MEMBER');
      const documentId = randomUUID();
      documents.set(userId, documentId);
      await api.createDocumentKey(userId, documentId);
    }
    const before = await Promise.all(
      users.map((userId) => snapshot(api, userId, [documents.get(userId)!])),
    );
    const oldRow = await inspect.userKey.findFirstOrThrow({ where: { userId: users[0]! } });
    const oldVersion = oldRow.masterKeyVersion;

    const run = await api.rotation.request({
      scope: 'USER_TYPE_MASTER',
      userType: 'MEMBER',
      trigger: 'MANUAL',
    });
    // The API's AppRole may not rotate a vault key.
    expect(await codeOf(api.rotation.run(run.id))).toBe('PERMISSION_DENIED');
    const progress = await worker.rotation.run(run.id);
    expect(progress.run.state).toBe('COMPLETED');
    const newVersion = progress.run.toVersion!;
    expect(newVersion).toBe(oldVersion + 1);

    const rows = await inspect.userKey.findMany({
      where: { masterKeyName: state.openbao.keys.userTypeMember, state: { not: 'DESTROYED' } },
    });
    expect(rows.every((row) => row.masterKeyVersion === newVersion)).toBe(true);
    const info = await vaultClient('api').getKeyInfo(state.openbao.keys.userTypeMember);
    expect(info.minDecryptionVersion).toBe(newVersion);

    // Readable from processes with empty caches…
    const after = await Promise.all(
      users.map((userId) => snapshot(realKeyring('api'), userId, [documents.get(userId)!])),
    );
    expect(after).toEqual(before);
    // …while the vault refuses the ciphertext made by the old key version.
    expect(
      await codeOf(
        vaultClient('api').decrypt(state.openbao.keys.userTypeMember, oldRow.wrappedKey!, users[0]),
      ),
    ).toBe('REJECTED');
    expect(
      await codeOf(
        vaultClient('worker').decrypt(
          state.openbao.keys.userTypeMember,
          oldRow.wrappedKey!,
          users[0],
        ),
      ),
    ).toBe('REJECTED');
  });
});
