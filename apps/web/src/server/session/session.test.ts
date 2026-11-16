import type { Tokens } from '@scanova/contracts/auth';
import { describe, expect, it, vi } from 'vitest';
import { clearedCookieAttributes, sessionCookieAttributes, sessionCookieName } from './cookie';
import { evaluateSession } from './evaluate';
import { ADMIN_SESSION_LIFETIME_MS, payloadFromTokens, type SessionPayload } from './payload';
import { RefreshCoordinator, type RefreshOutcome } from './refresh';
import { createSealer } from './seal';
import type { SessionEnvironment } from './evaluate';

const SECRET = 'a'.repeat(64);
const NOW = Date.parse('2026-11-16T12:00:00.000Z');
const USER_ID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f';

function tokens(
  overrides: { accessIn?: number; refreshIn?: number; userType?: 'ADMIN' | 'MEMBER' } = {},
): Tokens {
  return {
    accessToken: 'header.payload.signature',
    accessTokenExpiresAt: new Date(NOW + (overrides.accessIn ?? 600_000)).toISOString(),
    refreshToken: 'r'.repeat(43),
    refreshTokenExpiresAt: new Date(NOW + (overrides.refreshIn ?? 30 * 86_400_000)).toISOString(),
    user: {
      id: USER_ID,
      email: 'person@example.org',
      name: 'Person',
      userType: overrides.userType ?? 'MEMBER',
      status: 'ACTIVE',
      mustChangePassword: false,
      storageUsedBytes: 0,
      storageQuotaBytes: null,
      createdAt: new Date(NOW).toISOString(),
    },
  };
}

function payload(
  overrides: Parameters<typeof tokens>[0] = {},
  surface: 'site' | 'admin' = 'site',
): SessionPayload {
  return payloadFromTokens(tokens(overrides), surface, NOW);
}

describe('session cookie sealing', () => {
  const sealer = createSealer(SECRET);

  it('round-trips a session and keeps no e-mail address or name in it', () => {
    const sealed = sealer.seal(payload());
    expect(sealed.startsWith('v1.')).toBe(true);
    expect(sealer.unseal(sealed, 'site')).toEqual(payload());
    const plaintextish = Buffer.from(sealed.slice(3), 'base64url').toString('latin1');
    expect(plaintextish).not.toContain('person@example.org');
    expect(plaintextish).not.toContain('header.payload.signature');
  });

  it('uses a fresh nonce every time', () => {
    expect(sealer.seal(payload())).not.toBe(sealer.seal(payload()));
  });

  it('refuses tampering, truncation, another surface and another secret', () => {
    const sealed = sealer.seal(payload());
    const bytes = Buffer.from(sealed.slice(3), 'base64url');
    bytes[20] = (bytes[20] ?? 0) ^ 0x01;
    expect(sealer.unseal(`v1.${bytes.toString('base64url')}`, 'site')).toBeNull();
    expect(sealer.unseal(sealed.slice(0, 40), 'site')).toBeNull();
    expect(sealer.unseal(sealed, 'admin')).toBeNull();
    expect(createSealer('b'.repeat(64)).unseal(sealed, 'site')).toBeNull();
    expect(sealer.unseal(`v2.${sealed.slice(3)}`, 'site')).toBeNull();
    expect(sealer.unseal('', 'site')).toBeNull();
    expect(sealer.unseal(`v1.${'A'.repeat(5000)}`, 'site')).toBeNull();
  });

  it('refuses a cookie of one surface even when its payload claims the other', () => {
    const admin = sealer.seal(payload({ userType: 'ADMIN' }, 'admin'));
    expect(sealer.unseal(admin, 'site')).toBeNull();
    expect(sealer.unseal(admin, 'admin')?.surface).toBe('admin');
  });
});

describe('session cookie attributes', () => {
  it('is httpOnly, SameSite=Strict, host-bound with __Host- on https', () => {
    expect(sessionCookieName(true)).toBe('__Host-scanova-session');
    expect(sessionCookieName(false)).toBe('scanova-session');
    expect(sessionCookieAttributes(payload(), 'site', true, NOW)).toEqual({
      httpOnly: true,
      secure: true,
      sameSite: 'strict',
      path: '/',
      maxAge: 30 * 86_400,
    });
  });

  it('gives the admin console a browser-session cookie (no Max-Age)', () => {
    expect(
      sessionCookieAttributes(payload({ userType: 'ADMIN' }, 'admin'), 'admin', true, NOW),
    ).toEqual({
      httpOnly: true,
      secure: true,
      sameSite: 'strict',
      path: '/',
    });
    expect(clearedCookieAttributes(true).maxAge).toBe(0);
  });
});

describe('evaluateSession', () => {
  const sealer = createSealer(SECRET);
  const environment = (
    refresh: (token: string) => Promise<RefreshOutcome>,
    now = NOW,
  ): SessionEnvironment & { refresh: ReturnType<typeof vi.fn> } => ({
    sealer,
    refresh: vi.fn(refresh),
    now: () => now,
  });
  const never = (): Promise<RefreshOutcome> => Promise.reject(new Error('must not refresh'));

  it('has no session without a cookie, and clears one that is not ours', async () => {
    expect(await evaluateSession(undefined, 'site', null, environment(never))).toEqual({
      kind: 'none',
      clearCookie: false,
    });
    expect(await evaluateSession('v1.garbage', 'site', null, environment(never))).toEqual({
      kind: 'none',
      clearCookie: true,
    });
  });

  it('leaves a fresh access token alone', async () => {
    const env = environment(never);
    const state = await evaluateSession(sealer.seal(payload()), 'site', null, env);
    expect(state.kind).toBe('active');
    expect(env.refresh).not.toHaveBeenCalled();
  });

  it('refreshes within a minute of expiry and keeps the original sign-in time', async () => {
    const renewed = tokens({ accessIn: 600_000 });
    renewed.accessToken = 'new.access.token';
    const env = environment(
      () => Promise.resolve({ kind: 'refreshed', tokens: renewed }),
      NOW + 30_000,
    );
    const old = { ...payload({ accessIn: 45_000 }), issuedAt: NOW - 3_600_000 };
    const state = await evaluateSession(sealer.seal(old), 'site', '203.0.113.7', env);
    expect(env.refresh).toHaveBeenCalledWith(old.refreshToken, '203.0.113.7');
    expect(state.kind).toBe('refreshed');
    if (state.kind === 'refreshed') {
      expect(state.payload.accessToken).toBe('new.access.token');
      expect(state.payload.issuedAt).toBe(NOW - 3_600_000);
      expect(sealer.unseal(state.cookie, 'site')).toEqual(state.payload);
    }
  });

  it('ends the session when the API refuses the refresh token', async () => {
    const env = environment(() => Promise.resolve({ kind: 'rejected', code: 'SESSION_REVOKED' }));
    const state = await evaluateSession(
      sealer.seal(payload({ accessIn: 10_000 })),
      'site',
      null,
      env,
    );
    expect(state).toMatchObject({ kind: 'ended', reason: 'rejected' });
  });

  it('keeps going, unchanged, when the API is unreachable', async () => {
    const env = environment(() => Promise.resolve({ kind: 'unavailable' }));
    const state = await evaluateSession(
      sealer.seal(payload({ accessIn: 10_000 })),
      'site',
      null,
      env,
    );
    expect(state.kind).toBe('degraded');
  });

  it('ends a session whose refresh token has expired without asking the API', async () => {
    const env = environment(never, NOW + 31 * 86_400_000);
    const state = await evaluateSession(sealer.seal(payload()), 'site', null, env);
    expect(state).toMatchObject({ kind: 'ended', reason: 'expired' });
  });

  it('ends an admin session after its absolute lifetime, however fresh its tokens', async () => {
    const env = environment(never, NOW + ADMIN_SESSION_LIFETIME_MS + 1);
    const admin = {
      ...payload({ userType: 'ADMIN', accessIn: ADMIN_SESSION_LIFETIME_MS + 600_000 }, 'admin'),
    };
    const state = await evaluateSession(sealer.seal(admin), 'admin', null, env);
    expect(state).toMatchObject({ kind: 'ended', reason: 'lifetime' });
  });
});

describe('RefreshCoordinator', () => {
  const renewed: RefreshOutcome = { kind: 'refreshed', tokens: tokens() };

  it('exchanges a refresh token once, however many requests race', async () => {
    let resolve: (outcome: RefreshOutcome) => void = () => undefined;
    const refresh = vi.fn(
      () =>
        new Promise<RefreshOutcome>((done) => {
          resolve = done;
        }),
    );
    const coordinator = new RefreshCoordinator(refresh);
    const racing = [coordinator.refresh('token-a', null), coordinator.refresh('token-a', null)];
    resolve(renewed);
    expect(await Promise.all(racing)).toEqual([renewed, renewed]);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('answers a late request with the old token from memory within the grace period', async () => {
    let now = NOW;
    const refresh = vi.fn(() => Promise.resolve(renewed));
    const coordinator = new RefreshCoordinator(refresh, () => now, 30_000);
    await coordinator.refresh('token-a', null);
    now += 29_000;
    expect(await coordinator.refresh('token-a', null)).toBe(renewed);
    expect(refresh).toHaveBeenCalledTimes(1);
    now += 2_000;
    await coordinator.refresh('token-a', null);
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('does not remember an unreachable API, and turns a thrown error into "unavailable"', async () => {
    const refresh = vi
      .fn<(token: string, ip: string | null) => Promise<RefreshOutcome>>()
      .mockRejectedValueOnce(new Error('socket hang up'))
      .mockResolvedValueOnce({ kind: 'unavailable' })
      .mockResolvedValueOnce(renewed);
    const coordinator = new RefreshCoordinator(refresh);
    expect(await coordinator.refresh('token-a', null)).toEqual({ kind: 'unavailable' });
    expect(await coordinator.refresh('token-a', null)).toEqual({ kind: 'unavailable' });
    expect(await coordinator.refresh('token-a', null)).toBe(renewed);
    expect(refresh).toHaveBeenCalledTimes(3);
  });

  it('keeps tokens apart and bounds its memory', async () => {
    const refresh = vi.fn((token: string) =>
      Promise.resolve<RefreshOutcome>({ kind: 'rejected', code: token }),
    );
    const coordinator = new RefreshCoordinator(refresh, () => NOW, 30_000, 2);
    await coordinator.refresh('one', null);
    await coordinator.refresh('two', null);
    await coordinator.refresh('three', null);
    expect(await coordinator.refresh('two', null)).toEqual({ kind: 'rejected', code: 'two' });
    await coordinator.refresh('one', null);
    expect(refresh).toHaveBeenCalledTimes(4);
  });
});
