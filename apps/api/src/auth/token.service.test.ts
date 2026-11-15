import type { ApiConfig } from '@scanova/config/sections';
import { InMemoryKms } from '@scanova/kms/in-memory';
import { describe, expect, it } from 'vitest';
import { AppError } from '../common/app-error.js';
import { TokenService } from './token.service.js';

const SUB = '0b0e7a52-5c1d-4c53-9f0a-3d2e1f4a5b6c';
const SID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f';
const CLAIMS = { sub: SUB, typ: 'MEMBER', cli: 'mobile', sid: SID } as const;

function setup(): {
  tokens: TokenService;
  other: TokenService;
  kms: InMemoryKms;
  clock: { now: number };
} {
  const kms = new InMemoryKms();
  kms.createKey('jwt', { type: 'ed25519' });
  const clock = { now: Date.parse('2026-11-15T12:00:00Z') };
  const config = {
    auth: { jwtIssuer: 'scanova', accessTokenTtlSeconds: 600 },
    keys: { jwt: 'jwt' },
  } as unknown as ApiConfig;
  const now = (): Date => new Date(clock.now);
  return {
    tokens: new TokenService(kms, config, now),
    other: new TokenService(kms, config, now),
    kms,
    clock,
  };
}

async function code(action: Promise<unknown>): Promise<string> {
  try {
    await action;
  } catch (error) {
    return error instanceof AppError ? error.code : 'OTHER';
  }
  return 'OK';
}

function part(token: string, index: number): Record<string, unknown> {
  return JSON.parse(Buffer.from(token.split('.')[index]!, 'base64url').toString('utf8')) as Record<
    string,
    unknown
  >;
}

describe('TokenService', () => {
  it('issues an EdDSA token whose kid names the signing-key version, and verifies it', async () => {
    const { tokens, other } = setup();
    const issued = await tokens.issue(CLAIMS);
    expect(part(issued.token, 0)).toEqual({ alg: 'EdDSA', typ: 'JWT', kid: 'v1' });
    expect(part(issued.token, 1)).toEqual({
      ...CLAIMS,
      iss: 'scanova',
      iat: 1_794_744_000,
      exp: 1_794_744_600,
    });
    expect(issued.expiresAt.toISOString()).toBe('2026-11-15T12:10:00.000Z');
    // Verified locally by another process that only knows the public key.
    expect(await other.verify(issued.token)).toMatchObject(CLAIMS);
  });

  it('rejects tampering, another issuer, expiry and unsupported algorithms', async () => {
    const { tokens, clock } = setup();
    const { token } = await tokens.issue(CLAIMS);
    const [header, payload, signature] = token.split('.') as [string, string, string];

    const forgedPayload = Buffer.from(JSON.stringify({ ...part(token, 1), typ: 'ADMIN' })).toString(
      'base64url',
    );
    expect(await code(tokens.verify(`${header}.${forgedPayload}.${signature}`))).toBe(
      'UNAUTHENTICATED',
    );
    const noneHeader = Buffer.from(JSON.stringify({ alg: 'none', kid: 'v1' })).toString(
      'base64url',
    );
    expect(await code(tokens.verify(`${noneHeader}.${payload}.`))).toBe('UNAUTHENTICATED');
    const hsHeader = Buffer.from(JSON.stringify({ alg: 'HS256', kid: 'v1' })).toString('base64url');
    expect(await code(tokens.verify(`${hsHeader}.${payload}.${signature}`))).toBe(
      'UNAUTHENTICATED',
    );
    for (const garbage of [
      '',
      'abc',
      'a.b.c',
      `${header}.${payload}`,
      `${header}.${payload}.AAAA`,
    ]) {
      expect(await code(tokens.verify(garbage))).toBe('UNAUTHENTICATED');
    }
    const badKid = Buffer.from(JSON.stringify({ alg: 'EdDSA', kid: '../1' })).toString('base64url');
    expect(await code(tokens.verify(`${badKid}.${payload}.${signature}`))).toBe('UNAUTHENTICATED');

    clock.now += 604_000;
    expect(await code(tokens.verify(token))).toBe('OK');
    clock.now += 2_000;
    expect(await code(tokens.verify(token))).toBe('UNAUTHENTICATED');
  });

  it('follows a rotation: new kid for new tokens, old tokens valid until the version is retired', async () => {
    const { tokens, other, kms, clock } = setup();
    const before = await tokens.issue(CLAIMS);
    await kms.rotateKey('jwt');

    // Until its cache expires this process keeps signing with the version it knows — still valid.
    expect(part((await tokens.issue(CLAIMS)).token, 0).kid).toBe('v1');
    clock.now += 61_000;
    const after = await tokens.issue(CLAIMS);
    expect(part(after.token, 0).kid).toBe('v2');
    expect(await code(tokens.verify(before.token))).toBe('OK');
    // A process that has never seen v2 learns about it when such a token arrives.
    expect(await code(other.verify(after.token))).toBe('OK');

    await kms.setMinDecryptionVersion('jwt', 2);
    clock.now += 61_000;
    expect(await code(tokens.verify(before.token))).toBe('UNAUTHENTICATED');
    expect(await code(tokens.verify(after.token))).toBe('OK');
  });

  it('recovers when its cached signing version was retired underneath it', async () => {
    const { tokens, kms } = setup();
    await tokens.issue(CLAIMS);
    await kms.rotateKey('jwt');
    await kms.setMinDecryptionVersion('jwt', 2);
    const issued = await tokens.issue(CLAIMS);
    expect(part(issued.token, 0).kid).toBe('v2');
  });

  it('does not ask the vault again for every token with an unknown kid', async () => {
    const { tokens, kms } = setup();
    const { token } = await tokens.issue(CLAIMS);
    let lookups = 0;
    const original = kms.getKeyInfo.bind(kms);
    kms.getKeyInfo = (name: string) => {
      lookups += 1;
      return original(name);
    };
    const [, payload, signature] = token.split('.') as [string, string, string];
    const unknownKid = Buffer.from(JSON.stringify({ alg: 'EdDSA', kid: 'v99' })).toString(
      'base64url',
    );
    for (let attempt = 0; attempt < 20; attempt += 1) {
      expect(await code(tokens.verify(`${unknownKid}.${payload}.${signature}`))).toBe(
        'UNAUTHENTICATED',
      );
    }
    expect(lookups).toBe(0);
  });
});
