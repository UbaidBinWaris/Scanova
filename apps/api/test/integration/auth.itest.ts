import type { Tokens, User } from '@scanova/contracts/auth';
import type { Session } from '@scanova/contracts/me';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  DEVICE,
  SERVICE_HEADER,
  closeAllApps,
  closeInspectors,
  createAccount,
  createMember,
  mainDb,
  startApp,
  uniqueEmail,
  type TestApp,
} from './harness.js';

let api: TestApp;

beforeAll(async () => {
  api = await startApp();
});

afterAll(async () => {
  await closeAllApps();
  await closeInspectors();
});

function login(
  app: TestApp,
  email: string,
  password: string,
): ReturnType<TestApp['client']['post']> {
  return app.client.post('/api/v1/auth/login', {
    email,
    password,
    client: 'mobile',
    device: DEVICE,
  });
}

function claims(accessToken: string): Record<string, unknown> {
  return JSON.parse(
    Buffer.from(accessToken.split('.')[1]!, 'base64url').toString('utf8'),
  ) as Record<string, unknown>;
}

function header(accessToken: string): Record<string, unknown> {
  return JSON.parse(
    Buffer.from(accessToken.split('.')[0]!, 'base64url').toString('utf8'),
  ) as Record<string, unknown>;
}

describe('sign-in', () => {
  it('issues an EdDSA access token with the documented claims and a 32-byte refresh token', async () => {
    const member = await createMember(api);
    const response = await login(api, `  ${member.email.toUpperCase()} `, member.password);
    expect(response.status).toBe(200);
    const tokens = response.body?.data as Tokens;

    expect(header(tokens.accessToken)).toMatchObject({
      alg: 'EdDSA',
      typ: 'JWT',
      kid: expect.stringMatching(/^v\d+$/u) as string,
    });
    const payload = claims(tokens.accessToken);
    expect(Object.keys(payload).sort()).toEqual(['cli', 'exp', 'iat', 'iss', 'sid', 'sub', 'typ']);
    expect(payload).toMatchObject({
      sub: member.id,
      typ: 'MEMBER',
      cli: 'mobile',
      iss: 'scanova-test',
    });
    expect((payload.exp as number) - (payload.iat as number)).toBe(600);
    expect(Buffer.from(tokens.refreshToken, 'base64url')).toHaveLength(32);
    expect(tokens.user).toMatchObject({
      id: member.id,
      email: member.email,
      userType: 'MEMBER',
      status: 'ACTIVE',
      mustChangePassword: false,
    });

    // Only hashes are stored, and profile fields are ciphertext.
    const session = await mainDb().session.findUniqueOrThrow({
      where: { publicId: payload.sid as string },
    });
    expect(Buffer.from(session.refreshTokenHash)).toHaveLength(32);
    expect(Buffer.from(session.refreshTokenHash).toString('base64url')).not.toBe(
      tokens.refreshToken,
    );
    expect(Buffer.from(session.deviceNameEnc).includes(Buffer.from(DEVICE.name))).toBe(false);
    const user = await mainDb().user.findUniqueOrThrow({ where: { publicId: member.id } });
    expect(user.passwordHash).toMatch(/^\$argon2id\$/u);
    expect(Buffer.from(user.emailEnc).includes(Buffer.from(member.email))).toBe(false);
    expect(Buffer.from(user.emailIndex)).toHaveLength(32);
  });

  it('answers a wrong password and an unknown e-mail identically', async () => {
    const member = await createMember(api);
    const wrong = await login(api, member.email, 'not-the-password');
    const unknown = await login(api, 'nobody-here@example.test', 'not-the-password');
    for (const response of [wrong, unknown]) {
      expect(response.status).toBe(401);
      expect(response.body).toEqual({
        data: null,
        error: { code: 'INVALID_CREDENTIALS', message: 'E-mail or password is incorrect.' },
        meta: { nextCursor: null },
      });
    }
  });

  it('refuses an administrator on the mobile login without checking the password', async () => {
    const admin = await createAccount(api, 'ADMIN');
    // Right and wrong passwords get the same answer, so the public route cannot test admin passwords.
    const mobile = await login(api, admin.email, admin.temporaryPassword);
    expect(mobile.status).toBe(401);
    expect(mobile.body?.error?.code).toBe('INVALID_CREDENTIALS');
    const wrong = await login(api, admin.email, 'not-the-admin-password');
    expect(wrong.body?.error?.code).toBe('INVALID_CREDENTIALS');
    // The same credentials work on the internal web login, which issues cli=web.
    const web = await api.client
      .withHeaders(SERVICE_HEADER)
      .post<Tokens>('/internal/web/v1/auth/login', {
        email: admin.email,
        password: admin.temporaryPassword,
      });
    expect(web.status).toBe(200);
    expect(claims(web.body!.data.accessToken)).toMatchObject({ typ: 'ADMIN', cli: 'web' });
    // The wrong password still says nothing about the account type.
    expect((await login(api, admin.email, 'wrong')).body?.error?.code).toBe('INVALID_CREDENTIALS');
  });

  it('refuses a disabled account and stops its existing tokens', async () => {
    const member = await createMember(api);
    await mainDb().user.update({ where: { publicId: member.id }, data: { status: 'DISABLED' } });
    const response = await login(api, member.email, member.password);
    expect(response.status).toBe(403);
    expect(response.body?.error?.code).toBe('ACCOUNT_DISABLED');
    expect((await member.client.get('/api/v1/me')).body?.error?.code).toBe('ACCOUNT_DISABLED');
    expect(
      (await api.client.post('/api/v1/auth/refresh', { refreshToken: member.tokens.refreshToken }))
        .body?.error?.code,
    ).toBe('ACCOUNT_DISABLED');
  });

  it('rate-limits sign-in per IP and e-mail with a Retry-After header', async () => {
    const limited = await startApp({ auth: { ...api.config.auth, loginMaxAttemptsPerMinute: 3 } });
    const member = await createMember(limited);
    const statuses: number[] = [];
    for (let attempt = 0; attempt < 4; attempt += 1) {
      statuses.push((await login(limited, member.email, 'wrong-password')).status);
    }
    // createMember already used one attempt, which a successful sign-in resets.
    expect(statuses).toEqual([401, 401, 401, 429]);
    const blocked = await login(limited, member.email, member.password);
    expect(blocked.status).toBe(429);
    expect(blocked.body?.error?.code).toBe('RATE_LIMITED');
    expect(Number(blocked.headers.get('retry-after'))).toBeGreaterThan(0);
    expect(Number(blocked.headers.get('retry-after'))).toBeLessThanOrEqual(60);
    // Another e-mail from the same address is not affected.
    const other = await createMember(limited);
    expect((await login(limited, other.email, other.password)).status).toBe(200);
    // After the window the correct password works again.
    limited.advance(61_000);
    expect((await login(limited, member.email, member.password)).status).toBe(200);
    await limited.close();
  });

  it('re-hashes the password at sign-in when the Argon2 parameters changed', async () => {
    const member = await createMember(api);
    const before = (await mainDb().user.findUniqueOrThrow({ where: { publicId: member.id } }))
      .passwordHash;
    expect(before).toContain('m=8192,t=1,p=1');
    const stronger = await startApp({
      auth: { ...api.config.auth, argon2: { memoryKib: 9216, timeCost: 2, parallelism: 1 } },
    });
    expect((await login(stronger, member.email, member.password)).status).toBe(200);
    const after = (await mainDb().user.findUniqueOrThrow({ where: { publicId: member.id } }))
      .passwordHash;
    expect(after).toContain('m=9216,t=2,p=1');
    expect((await login(stronger, member.email, member.password)).status).toBe(200);
    await stronger.close();
  });
});

describe('forced password change', () => {
  it('lets a user with a temporary password do nothing but change it', async () => {
    const account = await createAccount(api, 'MEMBER');
    const first = await login(api, account.email, account.temporaryPassword);
    expect(first.status).toBe(200);
    const tokens = first.body?.data as Tokens;
    expect(tokens.user.mustChangePassword).toBe(true);
    const pending = api.client.as(tokens);

    // Reading the own account is allowed, so the app can show whose password must change.
    const me = await pending.get<User>('/api/v1/me');
    expect(me.status).toBe(200);
    expect(me.body!.data).toMatchObject({ email: account.email, mustChangePassword: true });
    // Refresh keeps working and still says so; it never answers 403 itself.
    const refreshed = await api.client.post<Tokens>('/api/v1/auth/refresh', {
      refreshToken: tokens.refreshToken,
    });
    expect(refreshed.status).toBe(200);
    expect(refreshed.body!.data.user.mustChangePassword).toBe(true);

    for (const [method, path] of [
      ['GET', '/api/v1/documents'],
      ['POST', '/api/v1/documents'],
      ['DELETE', `/api/v1/me/sessions/${account.id}`],
      ['GET', '/api/v1/me/sessions'],
      ['PATCH', '/api/v1/me'],
    ] as const) {
      const response = await pending.request(
        method,
        path,
        method === 'PATCH' || method === 'POST' ? { json: { name: 'x' } } : {},
      );
      expect(response.status).toBe(403);
      expect(response.body?.error?.code).toBe('PASSWORD_CHANGE_REQUIRED');
    }

    // Too short, the same as before, or a wrong current password do not lift the requirement.
    expect(
      (
        await pending.post('/api/v1/me/password', {
          currentPassword: account.temporaryPassword,
          newPassword: 'short',
        })
      ).body?.error?.code,
    ).toBe('VALIDATION_FAILED');
    expect(
      (
        await pending.post('/api/v1/me/password', {
          currentPassword: account.temporaryPassword,
          newPassword: account.temporaryPassword,
        })
      ).body?.error?.code,
    ).toBe('VALIDATION_FAILED');
    const wrongCurrent = await pending.post('/api/v1/me/password', {
      currentPassword: 'wrong',
      newPassword: 'a-long-new-password',
    });
    expect(wrongCurrent.status).toBe(401);
    expect(wrongCurrent.body?.error?.code).toBe('INVALID_CREDENTIALS');
    // The password policy: 12 to 256 characters, reported per field.
    const tooShort = await pending.post('/api/v1/me/password', {
      currentPassword: account.temporaryPassword,
      newPassword: 'elevenchars',
    });
    expect(tooShort.status).toBe(400);
    expect(tooShort.body?.error?.details).toEqual([
      { field: 'newPassword', message: expect.any(String) as string },
    ]);
    expect(
      (
        await pending.post('/api/v1/me/password', {
          currentPassword: account.temporaryPassword,
          newPassword: 'x'.repeat(257),
        })
      ).status,
    ).toBe(400);

    const changed = await pending.post<Tokens>('/api/v1/me/password', {
      currentPassword: account.temporaryPassword,
      newPassword: 'a-long-new-password',
    });
    expect(changed.status).toBe(200);
    expect(changed.body!.data.user.mustChangePassword).toBe(false);
    const after = await api.client.as(changed.body!.data).get<User>('/api/v1/me');
    expect(after.status).toBe(200);
    expect(after.body!.data).toMatchObject({ email: account.email, mustChangePassword: false });
    expect((await api.client.as(changed.body!.data).get('/api/v1/documents')).status).toBe(200);
    expect((await login(api, account.email, account.temporaryPassword)).status).toBe(401);
    expect((await login(api, account.email, 'a-long-new-password')).status).toBe(200);
  });

  it('signs every other device out when the password changes', async () => {
    const member = await createMember(api);
    const phone = (await login(api, member.email, member.password)).body?.data as Tokens;
    const tablet = (await login(api, member.email, member.password)).body?.data as Tokens;
    const changed = await api.client.as(phone).post<Tokens>('/api/v1/me/password', {
      currentPassword: member.password,
      newPassword: 'another-long-password',
    });
    expect(changed.status).toBe(200);

    expect((await api.client.as(tablet).get('/api/v1/me')).body?.error?.code).toBe(
      'SESSION_REVOKED',
    );
    expect(
      (await api.client.post('/api/v1/auth/refresh', { refreshToken: tablet.refreshToken })).body
        ?.error?.code,
    ).toBe('SESSION_REVOKED');
    // This device keeps working, with fresh tokens; its old refresh token is spent.
    expect((await api.client.as(changed.body!.data).get('/api/v1/me')).status).toBe(200);
    expect(
      (
        await api.client.post('/api/v1/auth/refresh', {
          refreshToken: changed.body!.data.refreshToken,
        })
      ).status,
    ).toBe(200);
  });
});

describe('refresh tokens', () => {
  it('rotates on every use and revokes the session when a spent token is replayed', async () => {
    const member = await createMember(api);
    const first = (await login(api, member.email, member.password)).body?.data as Tokens;

    const second = await api.client.post<Tokens>('/api/v1/auth/refresh', {
      refreshToken: first.refreshToken,
    });
    expect(second.status).toBe(200);
    expect(second.body!.data.refreshToken).not.toBe(first.refreshToken);
    expect(claims(second.body!.data.accessToken).sid).toBe(claims(first.accessToken).sid);
    const third = await api.client.post<Tokens>('/api/v1/auth/refresh', {
      refreshToken: second.body!.data.refreshToken,
    });
    expect(third.status).toBe(200);
    expect((await api.client.as(third.body!.data).get('/api/v1/me')).status).toBe(200);

    // An attacker replays the very first token, two rotations old.
    const replay = await api.client.post('/api/v1/auth/refresh', {
      refreshToken: first.refreshToken,
    });
    expect(replay.status).toBe(401);
    expect(replay.body?.error?.code).toBe('SESSION_REVOKED');

    // The whole session is gone: the newest refresh token and the access token stop working.
    expect(
      (
        await api.client.post('/api/v1/auth/refresh', {
          refreshToken: third.body!.data.refreshToken,
        })
      ).body?.error?.code,
    ).toBe('SESSION_REVOKED');
    expect((await api.client.as(third.body!.data).get('/api/v1/me')).body?.error?.code).toBe(
      'SESSION_REVOKED',
    );
    // Other sessions of the user are untouched.
    expect((await member.client.get('/api/v1/me')).status).toBe(200);

    const audit = await mainDb().auditEvent.findMany({
      where: { actor: { publicId: member.id }, action: 'REFRESH_TOKEN_REUSED' },
    });
    expect(audit).toHaveLength(1);
  });

  it('lets exactly one of two simultaneous uses win', async () => {
    const member = await createMember(api);
    const tokens = (await login(api, member.email, member.password)).body?.data as Tokens;
    const results = await Promise.all([
      api.client.post('/api/v1/auth/refresh', { refreshToken: tokens.refreshToken }),
      api.client.post('/api/v1/auth/refresh', { refreshToken: tokens.refreshToken }),
    ]);
    expect(results.map((result) => result.status).sort()).toEqual([200, 401]);
    expect(results.find((result) => result.status === 401)?.body?.error?.code).toBe(
      'SESSION_REVOKED',
    );
  });

  it('rejects unknown and expired refresh tokens', async () => {
    const unknown = await api.client.post('/api/v1/auth/refresh', {
      refreshToken: Buffer.alloc(32, 7).toString('base64url'),
    });
    expect(unknown.status).toBe(401);
    expect(unknown.body?.error?.code).toBe('INVALID_REFRESH_TOKEN');

    const member = await createMember(api);
    api.advance(31 * 86_400_000);
    const expired = await api.client.post('/api/v1/auth/refresh', {
      refreshToken: member.tokens.refreshToken,
    });
    expect(expired.body?.error?.code).toBe('INVALID_REFRESH_TOKEN');
    // The access token expired long ago as well.
    expect((await member.client.get('/api/v1/me')).body?.error?.code).toBe('UNAUTHENTICATED');
    api.advance(-31 * 86_400_000);
  });

  it('rejects an expired access token and accepts it again only via refresh', async () => {
    const member = await createMember(api);
    api.advance(601_000 + 6_000);
    expect((await member.client.get('/api/v1/me')).body?.error?.code).toBe('UNAUTHENTICATED');
    const refreshed = await api.client.post<Tokens>('/api/v1/auth/refresh', {
      refreshToken: member.tokens.refreshToken,
    });
    expect(refreshed.status).toBe(200);
    expect((await api.client.as(refreshed.body!.data).get('/api/v1/me')).status).toBe(200);
    api.advance(-607_000);
  });

  it('signs out idempotently', async () => {
    const member = await createMember(api);
    const tokens = (await login(api, member.email, member.password)).body?.data as Tokens;
    const out = await api.client.post('/api/v1/auth/logout', { refreshToken: tokens.refreshToken });
    expect(out.status).toBe(200);
    expect(out.body?.data).toEqual({ success: true });
    expect(
      (await api.client.post('/api/v1/auth/logout', { refreshToken: tokens.refreshToken })).body
        ?.data,
    ).toEqual({ success: true });
    expect((await api.client.as(tokens).get('/api/v1/me')).body?.error?.code).toBe(
      'SESSION_REVOKED',
    );
    expect(
      (await api.client.post('/api/v1/auth/refresh', { refreshToken: tokens.refreshToken })).body
        ?.error?.code,
    ).toBe('SESSION_REVOKED');
  });

  it('ends the session when sign-out sends a token that a refresh already exchanged', async () => {
    const member = await createMember(api);
    const old = (await login(api, member.email, member.password)).body?.data as Tokens;
    // The phone's refresh lands first; its sign-out still carries the previous token.
    const fresh = (
      await api.client.post('/api/v1/auth/refresh', { refreshToken: old.refreshToken })
    ).body?.data as Tokens;
    const out = await api.client.post('/api/v1/auth/logout', { refreshToken: old.refreshToken });
    expect(out.body?.data).toEqual({ success: true });
    expect((await api.client.as(fresh).get('/api/v1/me')).body?.error?.code).toBe(
      'SESSION_REVOKED',
    );
  });
});

describe('own account', () => {
  it('updates the name and lists and revokes own sessions only', async () => {
    const member = await createMember(api);
    const other = await createMember(api);
    const renamed = await member.client.patch<User>('/api/v1/me', { name: '  Ayesha Khan  ' });
    expect(renamed.status).toBe(200);
    expect(renamed.body!.data.name).toBe('Ayesha Khan');
    expect((await member.client.get<User>('/api/v1/me')).body!.data.name).toBe('Ayesha Khan');

    const second = (await login(api, member.email, member.password)).body?.data as Tokens;
    const sessions = await member.client.get<Session[]>('/api/v1/me/sessions');
    expect(sessions.status).toBe(200);
    expect(sessions.body!.data).toHaveLength(2);
    expect(sessions.body!.data.filter((session) => session.current)).toHaveLength(1);
    expect(sessions.body!.data[0]).toMatchObject({
      deviceName: 'Pixel 9',
      platform: 'android',
      model: 'Pixel 9 Pro',
    });

    const secondSessionId = claims(second.accessToken).sid as string;
    // Another user's session id is NOT_FOUND, and stays alive.
    expect((await other.client.delete(`/api/v1/me/sessions/${secondSessionId}`)).status).toBe(404);
    expect((await api.client.as(second).get('/api/v1/me')).status).toBe(200);

    expect(
      (await member.client.delete(`/api/v1/me/sessions/${secondSessionId}`)).body?.data,
    ).toEqual({ success: true });
    expect((await api.client.as(second).get('/api/v1/me')).body?.error?.code).toBe(
      'SESSION_REVOKED',
    );
    expect((await member.client.get<Session[]>('/api/v1/me/sessions')).body!.data).toHaveLength(1);
  });
});

describe('open sign-up', () => {
  const register = (
    testApp: TestApp,
    email: string,
    password: string = 'A-long-enough-password',
  ): ReturnType<TestApp['client']['post']> =>
    testApp.client.post('/api/v1/auth/register', {
      email,
      name: 'New Member',
      password,
      client: 'mobile',
      device: DEVICE,
    });

  it('creates a member with the sign-up quota and signs it in at once', async () => {
    const email = uniqueEmail('signup');
    const response = await register(api, email);
    expect(response.status).toBe(201);
    const tokens = response.body?.data as Tokens;
    expect(tokens.user).toMatchObject({
      email,
      userType: 'MEMBER',
      mustChangePassword: false,
      storageQuotaBytes: api.config.signup.quotaBytes,
    });
    expect((await api.client.as(tokens).get('/api/v1/documents')).status).toBe(200);
    // The same credentials work on the normal sign-in.
    expect((await login(api, email, 'A-long-enough-password')).status).toBe(200);
  });

  it('refuses an e-mail that already has an account', async () => {
    const email = uniqueEmail('signup-twice');
    expect((await register(api, email)).status).toBe(201);
    const again = await register(api, email);
    expect(again.status).toBe(409);
    expect(again.body?.error?.code).toBe('ID_CONFLICT');
  });

  it('enforces the password policy', async () => {
    const response = await register(api, uniqueEmail('signup-short'), 'short');
    expect(response.status).toBe(400);
    expect(response.body?.error?.code).toBe('VALIDATION_FAILED');
  });

  it('answers FORBIDDEN when sign-up is closed', async () => {
    const closed = await startApp({ signup: { ...api.config.signup, enabled: false } });
    const response = await register(closed, uniqueEmail('signup-closed'));
    expect(response.status).toBe(403);
    expect(response.body?.error?.code).toBe('FORBIDDEN');
  });

  it('limits sign-ups per IP per hour before doing any work', async () => {
    const limited = await startApp({ signup: { ...api.config.signup, maxPerIpPerHour: 2 } });
    expect((await register(limited, uniqueEmail('signup-a'))).status).toBe(201);
    expect((await register(limited, uniqueEmail('signup-b'))).status).toBe(201);
    const third = await register(limited, uniqueEmail('signup-c'));
    expect(third.status).toBe(429);
    expect(third.body?.error?.code).toBe('RATE_LIMITED');
  });
});
