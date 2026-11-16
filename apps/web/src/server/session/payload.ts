import type { Tokens } from '@scanova/contracts/auth';
import { UserTypeSchema } from '@scanova/contracts/enums';
import { z } from 'zod';
import type { Surface } from '@/lib/surface';

/**
 * What the encrypted session cookie holds. Tokens never reach browser
 * JavaScript: the cookie is httpOnly and its content is AES-256-GCM sealed.
 * Names and e-mail addresses are not kept here; pages read them from `GET /me`.
 */
export const SessionPayloadSchema = z.object({
  v: z.literal(1),
  surface: z.enum(['site', 'admin']),
  accessToken: z.string().min(1).max(4096),
  accessTokenExpiresAt: z.number().int(),
  refreshToken: z.string().min(20).max(512),
  refreshTokenExpiresAt: z.number().int(),
  /** Sign-in time; the admin console has an absolute session lifetime. */
  issuedAt: z.number().int(),
  userId: z.string().min(1).max(64),
  userType: UserTypeSchema,
  mustChangePassword: z.boolean(),
});
export type SessionPayload = z.infer<typeof SessionPayloadSchema>;

/** An admin signs in again at least every 12 hours, however active the session is. */
export const ADMIN_SESSION_LIFETIME_MS = 12 * 60 * 60 * 1000;

/** Refresh this long before the access token expires, so a page render never meets an expired one. */
export const REFRESH_AHEAD_MS = 60_000;

export function payloadFromTokens(
  tokens: Tokens,
  surface: Surface,
  issuedAt: number,
): SessionPayload {
  return {
    v: 1,
    surface,
    accessToken: tokens.accessToken,
    accessTokenExpiresAt: Date.parse(tokens.accessTokenExpiresAt),
    refreshToken: tokens.refreshToken,
    refreshTokenExpiresAt: Date.parse(tokens.refreshTokenExpiresAt),
    issuedAt,
    userId: tokens.user.id,
    userType: tokens.user.userType,
    mustChangePassword: tokens.user.mustChangePassword,
  };
}

/** When the session ends at the latest (refresh token expiry, or the admin lifetime). */
export function sessionDeadline(payload: SessionPayload): number {
  return payload.surface === 'admin'
    ? Math.min(payload.refreshTokenExpiresAt, payload.issuedAt + ADMIN_SESSION_LIFETIME_MS)
    : payload.refreshTokenExpiresAt;
}
