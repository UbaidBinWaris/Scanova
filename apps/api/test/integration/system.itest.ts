import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  SERVICE_HEADER,
  closeAllApps,
  closeInspectors,
  createDocument,
  createMember,
  startApp,
  type TestApp,
  type TestUser,
} from './harness.js';

let api: TestApp;
let member: TestUser;

beforeAll(async () => {
  api = await startApp();
  member = await createMember(api);
});

afterAll(async () => {
  await closeAllApps();
  await closeInspectors();
});

describe('health and server info', () => {
  it('answers liveness without authentication, in the envelope', async () => {
    const response = await api.client.get('/api/v1/health');
    expect(response.status).toBe(200);
    expect(response.body).toEqual({
      data: { status: 'ok' },
      error: null,
      meta: { nextCursor: null },
    });
  });

  it('reports readiness of database, storage and vault', async () => {
    const response = await api.client.get('/api/v1/health/ready');
    expect(response.status).toBe(200);
    expect(response.body?.data).toEqual({
      status: 'ok',
      checks: { database: 'ok', storage: 'ok', kms: 'ok' },
    });
  });

  it('answers 503 with the failing check when a dependency is down', async () => {
    const broken = await startApp({
      kms: {
        address: 'http://127.0.0.1:9',
        transitMount: 'transit',
        caCertPath: null,
        roleId: 'x',
        secretId: 'y',
      },
    });
    const response = await broken.client.get('/api/v1/health/ready');
    expect(response.status).toBe(503);
    expect(response.body).toMatchObject({
      data: null,
      error: {
        code: 'SERVICE_UNAVAILABLE',
        details: { checks: { database: 'ok', storage: 'ok', kms: 'unavailable' } },
      },
    });
    // Liveness does not depend on the vault.
    expect((await broken.client.get('/api/v1/health')).status).toBe(200);
    // A request that needs the vault is a 503, not a 500.
    const login = await broken.client.post('/api/v1/auth/login', {
      email: 'nobody@example.test',
      password: 'x',
      client: 'mobile',
      device: { name: 'a', platform: 'android', model: 'b', appVersion: '1' },
    });
    expect(login.status).toBe(503);
    expect(login.body?.error?.code).toBe('SERVICE_UNAVAILABLE');
    await broken.close();
  });

  it('describes the server', async () => {
    const response = await api.client.get('/api/v1/server-info');
    expect(response.status).toBe(200);
    expect(response.body?.data).toEqual({
      name: 'Scanova',
      apiVersion: '1',
      serverVersion: '0.1.0',
      features: { serverOcr: true, ai: false },
    });
  });
});

describe('envelope and error mapping', () => {
  it('answers unknown routes with NOT_FOUND in the envelope', async () => {
    const response = await api.client.get('/api/v1/nope');
    expect(response.status).toBe(404);
    expect(response.body).toEqual({
      data: null,
      error: { code: 'NOT_FOUND', message: 'Not found.' },
      meta: { nextCursor: null },
    });
  });

  it('requires an access token everywhere else', async () => {
    for (const path of [
      '/api/v1/me',
      '/api/v1/documents',
      '/api/v1/me/sessions',
      `/api/v1/documents/${randomUUID()}`,
    ]) {
      const response = await api.client.get(path);
      expect(response.status).toBe(401);
      expect(response.body?.error?.code).toBe('UNAUTHENTICATED');
    }
    const garbage = await api.client.as({ accessToken: 'not.a.token' }).get('/api/v1/me');
    expect(garbage.body?.error?.code).toBe('UNAUTHENTICATED');
    // A token signed by someone else, with a plausible header.
    const forged = `${Buffer.from(JSON.stringify({ alg: 'EdDSA', typ: 'JWT', kid: 'v1' })).toString('base64url')}.${Buffer.from(JSON.stringify({ sub: member.id, typ: 'MEMBER', cli: 'mobile', sid: randomUUID(), iss: 'scanova-test', iat: 1, exp: 9_999_999_999 })).toString('base64url')}.${Buffer.alloc(64, 1).toString('base64url')}`;
    expect((await api.client.as({ accessToken: forged }).get('/api/v1/me')).body?.error?.code).toBe(
      'UNAUTHENTICATED',
    );
    // "alg: none" is not accepted.
    const none = `${Buffer.from(JSON.stringify({ alg: 'none', kid: 'v1' })).toString('base64url')}.${forged.split('.')[1]}.`;
    expect((await api.client.as({ accessToken: none }).get('/api/v1/me')).status).toBe(401);
  });

  it('lists the invalid fields of a request and never echoes their values', async () => {
    const response = await member.client.post('/api/v1/documents', {
      id: 'not-a-uuid',
      title: '',
      pageCount: 0,
      capturedAt: 'yesterday',
      secretNote: 'do-not-echo-this',
    });
    expect(response.status).toBe(400);
    expect(response.body?.error?.code).toBe('VALIDATION_FAILED');
    const details = response.body?.error?.details as { field: string; message: string }[];
    expect(details.map((detail) => detail.field).sort()).toEqual([
      'capturedAt',
      'id',
      'pageCount',
      'title',
    ]);
    expect(response.raw.toString()).not.toContain('not-a-uuid');
    expect(response.raw.toString()).not.toContain('yesterday');
  });

  it('answers malformed JSON, a missing body and a bad id with VALIDATION_FAILED', async () => {
    const malformed = await member.client.request('POST', '/api/v1/documents', {
      body: Buffer.from('{"id": '),
      headers: { 'content-type': 'application/json' },
    });
    expect(malformed.status).toBe(400);
    expect(malformed.body?.error?.code).toBe('VALIDATION_FAILED');

    const empty = await member.client.request('PATCH', '/api/v1/me');
    expect(empty.status).toBe(400);
    expect(empty.body?.error?.code).toBe('VALIDATION_FAILED');

    const badId = await member.client.get('/api/v1/documents/1234');
    expect(badId.status).toBe(400);
    expect(badId.body?.error?.details).toEqual([
      { field: 'id', message: expect.any(String) as string },
    ]);

    const badQuery = await member.client.get('/api/v1/documents?limit=0');
    expect(badQuery.body?.error?.code).toBe('VALIDATION_FAILED');
    const badCursor = await member.client.get('/api/v1/documents?cursor=bm90LWpzb24');
    expect(badCursor.body?.error?.code).toBe('VALIDATION_FAILED');
  });

  it('rejects an unsupported content type with UNSUPPORTED_MEDIA_TYPE', async () => {
    const response = await member.client.request('POST', '/api/v1/documents', {
      body: Buffer.from('<xml/>'),
      headers: { 'content-type': 'application/xml' },
    });
    expect(response.status).toBe(415);
    expect(response.body?.error?.code).toBe('UNSUPPORTED_MEDIA_TYPE');
  });
});

describe('logging', () => {
  it('logs ids and codes, and never secrets, e-mail addresses, titles or text', async () => {
    const app = await startApp();
    const user = await createMember(app);
    const title = 'Tax return for Uncle Scrooge';
    const text = 'confidential-ocr-text-marker';
    const { id } = await createDocument(user, {
      title,
      tags: ['private-tag-marker'],
      clientOcrText: text,
    });
    await user.client.get(`/api/v1/documents?q=${encodeURIComponent('scrooge')}`);
    await user.client.get(`/api/v1/documents/${id}/file`);
    await app.client.post('/api/v1/auth/login', {
      email: user.email,
      password: 'wrong-password-marker',
      client: 'mobile',
      device: { name: 'device-name-marker', platform: 'android', model: 'm', appVersion: '1' },
    });
    await app.client
      .withHeaders(SERVICE_HEADER)
      .post('/internal/web/v1/auth/login', { email: user.email, password: user.password });

    const log = app.logs.join('');
    expect(app.logs.length).toBeGreaterThan(5);
    expect(log).toContain('"event":"request.completed"');
    expect(log).toContain('/api/v1/documents/:id/file');
    expect(log).toContain(user.id);
    for (const secret of [
      user.email,
      user.password,
      'wrong-password-marker',
      'device-name-marker',
      title,
      'Scrooge',
      'scrooge',
      text,
      'private-tag-marker',
      user.tokens.accessToken,
      user.tokens.refreshToken,
      app.config.auth.webServiceToken,
      app.config.storage.secretAccessKey,
      app.config.kms.secretId,
      app.config.database.password,
    ]) {
      expect(log).not.toContain(secret);
    }
    // The document id appears only as an id field, never inside a URL with a query string.
    expect(log).not.toMatch(/\?q=/u);
    await app.close();
  });
});
