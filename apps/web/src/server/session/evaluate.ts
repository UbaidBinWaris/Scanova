import type { Surface } from '@/lib/surface';
import {
  payloadFromTokens,
  REFRESH_AHEAD_MS,
  sessionDeadline,
  type SessionPayload,
} from './payload';
import type { RefreshOutcome } from './refresh';
import type { Sealer } from './seal';

export type SessionState =
  /** No cookie, or one that is not ours (tampered, other surface, old secret): clear it. */
  | { readonly kind: 'none'; readonly clearCookie: boolean }
  | { readonly kind: 'active'; readonly payload: SessionPayload }
  /** The access token was renewed: the caller sets this new cookie. */
  | { readonly kind: 'refreshed'; readonly payload: SessionPayload; readonly cookie: string }
  /** The session is over; `lifetime` means it is still valid at the API and should be revoked. */
  | {
      readonly kind: 'ended';
      readonly payload: SessionPayload;
      readonly reason: 'expired' | 'rejected' | 'lifetime';
    }
  /** Renewal was needed but the API was unreachable; the request goes on as it is. */
  | { readonly kind: 'degraded'; readonly payload: SessionPayload };

export interface SessionEnvironment {
  readonly sealer: Sealer;
  readonly refresh: (refreshToken: string, clientIp: string | null) => Promise<RefreshOutcome>;
  readonly now: () => number;
}

/** Decides what to do with the session cookie of one request (run by the proxy). */
export async function evaluateSession(
  cookie: string | undefined,
  surface: Surface,
  clientIp: string | null,
  environment: SessionEnvironment,
): Promise<SessionState> {
  if (cookie === undefined || cookie === '') {
    return { kind: 'none', clearCookie: false };
  }
  const payload = environment.sealer.unseal(cookie, surface);
  if (payload === null) {
    return { kind: 'none', clearCookie: true };
  }
  const now = environment.now();
  if (payload.refreshTokenExpiresAt <= now) {
    return { kind: 'ended', payload, reason: 'expired' };
  }
  if (sessionDeadline(payload) <= now) {
    return { kind: 'ended', payload, reason: 'lifetime' };
  }
  if (payload.accessTokenExpiresAt - now > REFRESH_AHEAD_MS) {
    return { kind: 'active', payload };
  }
  const outcome = await environment.refresh(payload.refreshToken, clientIp);
  switch (outcome.kind) {
    case 'refreshed': {
      const next = payloadFromTokens(outcome.tokens, surface, payload.issuedAt);
      return { kind: 'refreshed', payload: next, cookie: environment.sealer.seal(next) };
    }
    case 'rejected':
      return { kind: 'ended', payload, reason: 'rejected' };
    case 'unavailable':
      return { kind: 'degraded', payload };
  }
}
