import { createPublicKey, type KeyObject } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import { AccessTokenClaimsSchema, type AccessTokenClaims } from '@scanova/contracts/auth';
import { KmsError } from '@scanova/kms/errors';
import type { Kms } from '@scanova/kms/kms';
import { decodeProtectedHeader, jwtVerify } from 'jose';
import { AppError } from '../common/app-error.js';
import { API_CONFIG, CLOCK, KMS, type Clock } from '../platform/tokens.js';

const ALGORITHM = 'EdDSA';
/** How long the signing key's versions and public keys are trusted before asking the vault again. */
const KEY_CACHE_TTL_MS = 60_000;
/** An unknown `kid` triggers a refresh, but not more often than this (no vault flood from bad tokens). */
const MIN_REFRESH_INTERVAL_MS = 5_000;
const CLOCK_TOLERANCE_SECONDS = 5;
const KID_PATTERN = /^v([1-9]\d{0,8})$/u;

interface SigningKeys {
  readonly latestVersion: number;
  readonly publicKeys: ReadonlyMap<number, KeyObject>;
  readonly fetchedAt: number;
}

export interface IssuedAccessToken {
  readonly token: string;
  readonly expiresAt: Date;
}

/**
 * Access tokens (§5.5): EdDSA JWTs. The private key never leaves the vault, so
 * signing is a Transit call; verification is local, with the public keys of
 * every non-retired version cached and selected by the `kid` header.
 */
@Injectable()
export class TokenService {
  private keys: SigningKeys | null = null;
  private refreshing: Promise<SigningKeys> | null = null;

  public constructor(
    @Inject(KMS) private readonly kms: Kms,
    @Inject(API_CONFIG) private readonly config: ApiConfig,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  public async issue(
    claims: Pick<AccessTokenClaims, 'sub' | 'typ' | 'cli' | 'sid'>,
  ): Promise<IssuedAccessToken> {
    const issuedAt = Math.floor(this.clock().getTime() / 1000);
    const expiresAt = issuedAt + this.config.auth.accessTokenTtlSeconds;
    const payload: AccessTokenClaims = {
      ...claims,
      iss: this.config.auth.jwtIssuer,
      iat: issuedAt,
      exp: expiresAt,
    };
    let keys = await this.signingKeys();
    for (let attempt = 0; ; attempt += 1) {
      try {
        return {
          token: await this.sign(payload, keys.latestVersion),
          expiresAt: new Date(expiresAt * 1000),
        };
      } catch (error) {
        // The cached version was retired by a rotation: look again, once.
        if (attempt > 0 || !(error instanceof KmsError) || error.code !== 'REJECTED') {
          throw error;
        }
        keys = await this.refresh();
      }
    }
  }

  /** Verifies signature, issuer, expiry and claim shape. Throws `UNAUTHENTICATED` for anything else. */
  public async verify(token: string): Promise<AccessTokenClaims> {
    let version: number;
    try {
      const header = decodeProtectedHeader(token);
      const match = typeof header.kid === 'string' ? KID_PATTERN.exec(header.kid) : null;
      if (header.alg !== ALGORITHM || match === null) {
        throw new AppError('UNAUTHENTICATED');
      }
      version = Number(match[1]);
    } catch {
      throw new AppError('UNAUTHENTICATED');
    }
    const publicKey = await this.publicKey(version);
    if (publicKey === null) {
      throw new AppError('UNAUTHENTICATED');
    }
    try {
      const { payload } = await jwtVerify(token, publicKey, {
        algorithms: [ALGORITHM],
        issuer: this.config.auth.jwtIssuer,
        currentDate: this.clock(),
        clockTolerance: CLOCK_TOLERANCE_SECONDS,
        requiredClaims: ['sub', 'sid', 'exp', 'iat'],
      });
      return AccessTokenClaimsSchema.parse(payload);
    } catch {
      throw new AppError('UNAUTHENTICATED');
    }
  }

  private async sign(payload: AccessTokenClaims, version: number): Promise<string> {
    const header = { alg: ALGORITHM, typ: 'JWT', kid: `v${version}` };
    const signingInput = `${encode(header)}.${encode(payload)}`;
    // The version is pinned so the `kid` in the header is the key that actually signed.
    const { signature } = await this.kms.sign(
      this.config.keys.jwt,
      Buffer.from(signingInput, 'ascii'),
      version,
    );
    return `${signingInput}.${signature.toString('base64url')}`;
  }

  private async publicKey(version: number): Promise<KeyObject | null> {
    const keys = await this.signingKeys();
    const known = keys.publicKeys.get(version);
    if (known !== undefined) {
      return known;
    }
    // A version this process has not seen yet: a rotation may just have happened.
    if (
      version > keys.latestVersion &&
      this.clock().getTime() - keys.fetchedAt >= MIN_REFRESH_INTERVAL_MS
    ) {
      return (await this.refresh()).publicKeys.get(version) ?? null;
    }
    return null;
  }

  private async signingKeys(): Promise<SigningKeys> {
    if (this.keys !== null && this.clock().getTime() - this.keys.fetchedAt < KEY_CACHE_TTL_MS) {
      return this.keys;
    }
    return this.refresh();
  }

  private refresh(): Promise<SigningKeys> {
    this.refreshing ??= this.load().finally(() => {
      this.refreshing = null;
    });
    return this.refreshing;
  }

  private async load(): Promise<SigningKeys> {
    const info = await this.kms.getKeyInfo(this.config.keys.jwt);
    const publicKeys = new Map<number, KeyObject>();
    for (const version of info.versions) {
      // Versions below min_decryption_version are retired and must not verify any more.
      if (version.publicKey !== null && version.version >= info.minDecryptionVersion) {
        publicKeys.set(
          version.version,
          createPublicKey({
            key: { kty: 'OKP', crv: 'Ed25519', x: version.publicKey.toString('base64url') },
            format: 'jwk',
          }),
        );
      }
    }
    this.keys = {
      latestVersion: info.latestVersion,
      publicKeys,
      fetchedAt: this.clock().getTime(),
    };
    return this.keys;
  }
}

function encode(value: object): string {
  return Buffer.from(JSON.stringify(value), 'utf8').toString('base64url');
}
