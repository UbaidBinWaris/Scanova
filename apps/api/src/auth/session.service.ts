import { randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import type { Session as SessionDto } from '@scanova/contracts/me';
import { decryptFieldToString, encryptField } from '@scanova/crypto/field';
import { randomToken, sha256 } from '@scanova/crypto/random';
import type { DatabaseClient, Session, SessionClient, User } from '@scanova/database/client';
import type { Keyring } from '@scanova/keyring/keyring';
import { AppError } from '../common/app-error.js';
import { API_CONFIG, CLOCK, DATABASE, KEYRING, type Clock } from '../platform/tokens.js';
import { FIELDS } from '@scanova/crypto/bindings';

export interface DeviceInfo {
  readonly name: string;
  readonly model: string;
  readonly platform: string;
  readonly appVersion: string | null;
}

export interface IssuedRefreshToken {
  readonly token: string;
  readonly expiresAt: Date;
}

export interface SessionWithUser extends Session {
  readonly user: User;
}

export type RefreshOutcome =
  | {
      readonly kind: 'ROTATED';
      readonly session: SessionWithUser;
      readonly refresh: IssuedRefreshToken;
    }
  /** A spent token was presented: the session has been revoked. */
  | { readonly kind: 'REUSED'; readonly session: SessionWithUser }
  | { readonly kind: 'REVOKED'; readonly session: SessionWithUser }
  | { readonly kind: 'UNKNOWN' };

const REFRESH_TOKEN_BYTES = 32;
const DAY_MS = 86_400_000;

/**
 * Sessions and refresh tokens (§5.5). A refresh token is 32 random bytes, stored
 * only as a SHA-256 hash, and valid exactly once. Every spent hash is kept for
 * the lifetime of its session, so presenting any earlier token — not just the
 * previous one — is recognised as a replay and revokes the session.
 */
@Injectable()
export class SessionService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(API_CONFIG) private readonly config: ApiConfig,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  public async create(
    user: User,
    client: SessionClient,
    device: DeviceInfo,
  ): Promise<{ session: Session; refresh: IssuedRefreshToken }> {
    const publicId = randomUUID();
    const refresh = this.newRefreshToken();
    const encrypted = await this.keyring.useProfileKey(user.publicId, (key) => ({
      deviceNameEnc: encryptField(key, FIELDS.sessionDeviceName(publicId), device.name),
      deviceModelEnc: encryptField(key, FIELDS.sessionDeviceModel(publicId), device.model),
    }));
    const now = this.clock();
    const session = await this.database.session.create({
      data: {
        publicId,
        userId: user.id,
        client,
        refreshTokenHash: hashToken(refresh.token),
        refreshExpiresAt: refresh.expiresAt,
        ...encrypted,
        platform: device.platform,
        appVersion: device.appVersion,
        lastUsedAt: now,
        createdAt: now,
      },
    });
    return { session, refresh };
  }

  /** Exchanges a refresh token for a new one, or reports why it cannot be. */
  public async rotate(refreshToken: string): Promise<RefreshOutcome> {
    const presented = hashToken(refreshToken);
    const now = this.clock();
    const session = await this.database.session.findUnique({
      where: { refreshTokenHash: presented },
      include: { user: true },
    });
    if (session === null) {
      const spent = await this.database.spentRefreshToken.findUnique({
        where: { tokenHash: presented },
        include: { session: { include: { user: true } } },
      });
      if (spent === null) {
        return { kind: 'UNKNOWN' };
      }
      // Someone is replaying a token that was already exchanged: treat the session as stolen.
      await this.revoke(spent.session.id);
      return { kind: 'REUSED', session: spent.session };
    }
    if (session.revokedAt !== null) {
      return { kind: 'REVOKED', session };
    }
    if (session.refreshExpiresAt.getTime() <= now.getTime()) {
      return { kind: 'UNKNOWN' };
    }
    const refresh = this.newRefreshToken();
    const rotated = await this.database.$transaction(async (tx) => {
      // Conditional on the presented hash: of two concurrent uses, exactly one wins.
      const updated = await tx.session.updateMany({
        where: { id: session.id, refreshTokenHash: presented, revokedAt: null },
        data: {
          refreshTokenHash: hashToken(refresh.token),
          refreshExpiresAt: refresh.expiresAt,
          lastUsedAt: now,
        },
      });
      if (updated.count === 1) {
        await tx.spentRefreshToken.create({
          data: { sessionId: session.id, tokenHash: presented, spentAt: now },
        });
      }
      return updated.count === 1;
    });
    if (!rotated) {
      // The same token was used twice at the same moment: the loser is a replay.
      await this.revoke(session.id);
      return { kind: 'REUSED', session };
    }
    return { kind: 'ROTATED', session, refresh };
  }

  /** New refresh token for an existing session (after a password change). */
  public async reissue(sessionId: number): Promise<IssuedRefreshToken> {
    const refresh = this.newRefreshToken();
    const now = this.clock();
    await this.database.$transaction(async (tx) => {
      const current = await tx.session.findUniqueOrThrow({ where: { id: sessionId } });
      await tx.spentRefreshToken.create({
        data: { sessionId, tokenHash: current.refreshTokenHash, spentAt: now },
      });
      await tx.session.update({
        where: { id: sessionId },
        data: {
          refreshTokenHash: hashToken(refresh.token),
          refreshExpiresAt: refresh.expiresAt,
          lastUsedAt: now,
        },
      });
    });
    return refresh;
  }

  public findByRefreshToken(refreshToken: string): Promise<Session | null> {
    return this.database.session.findUnique({
      where: { refreshTokenHash: hashToken(refreshToken) },
    });
  }

  /**
   * The session a refresh token belongs to, whether it is the current token or
   * one already exchanged. Sign-out uses this: a phone that signs out while its
   * own refresh is in flight holds the previous token, and must still end the session.
   */
  public async findByAnyRefreshToken(refreshToken: string): Promise<Session | null> {
    const current = await this.findByRefreshToken(refreshToken);
    if (current !== null) {
      return current;
    }
    const spent = await this.database.spentRefreshToken.findUnique({
      where: { tokenHash: hashToken(refreshToken) },
      include: { session: true },
    });
    return spent?.session ?? null;
  }

  public async revoke(sessionId: number): Promise<void> {
    await this.database.session.updateMany({
      where: { id: sessionId, revokedAt: null },
      data: { revokedAt: this.clock() },
    });
  }

  /** Revokes every live session of a user, optionally keeping one. Returns how many. */
  public async revokeAll(userId: number, exceptSessionId?: number): Promise<number> {
    const result = await this.database.session.updateMany({
      where: {
        userId,
        revokedAt: null,
        ...(exceptSessionId === undefined ? {} : { id: { not: exceptSessionId } }),
      },
      data: { revokedAt: this.clock() },
    });
    return result.count;
  }

  /** Live sessions of a user, newest first, with device details decrypted. */
  public async list(
    user: { id: number; publicId: string },
    currentSessionId: number,
  ): Promise<SessionDto[]> {
    const sessions = await this.database.session.findMany({
      where: { userId: user.id, revokedAt: null, refreshExpiresAt: { gt: this.clock() } },
      orderBy: [{ lastUsedAt: 'desc' }, { id: 'desc' }],
    });
    return this.keyring.useProfileKey(user.publicId, (key) =>
      sessions.map((session) => ({
        id: session.publicId,
        deviceName: decryptFieldToString(
          key,
          FIELDS.sessionDeviceName(session.publicId),
          session.deviceNameEnc,
        ),
        platform: session.platform,
        model: decryptFieldToString(
          key,
          FIELDS.sessionDeviceModel(session.publicId),
          session.deviceModelEnc,
        ),
        createdAt: session.createdAt.toISOString(),
        lastUsedAt: session.lastUsedAt.toISOString(),
        current: session.id === currentSessionId,
      })),
    );
  }

  /** Revokes one of the user's own sessions. Another user's id is `NOT_FOUND`. */
  public async revokeOwned(userId: number, sessionPublicId: string): Promise<void> {
    const session = await this.database.session.findFirst({
      where: { publicId: sessionPublicId, userId },
      select: { id: true },
    });
    if (session === null) {
      throw new AppError('NOT_FOUND');
    }
    await this.revoke(session.id);
  }

  private newRefreshToken(): IssuedRefreshToken {
    return {
      token: randomToken(REFRESH_TOKEN_BYTES),
      expiresAt: new Date(this.clock().getTime() + this.config.auth.refreshTokenTtlDays * DAY_MS),
    };
  }
}

function hashToken(token: string): Buffer<ArrayBuffer> {
  return sha256(token);
}
