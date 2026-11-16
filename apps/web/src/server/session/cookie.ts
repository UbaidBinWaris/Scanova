import type { Surface } from '@/lib/surface';
import { sessionDeadline, type SessionPayload } from './payload';

export interface CookieAttributes {
  readonly httpOnly: true;
  readonly secure: boolean;
  readonly sameSite: 'strict';
  readonly path: '/';
  readonly maxAge?: number;
}

/**
 * `__Host-` binds the cookie to exactly this host over HTTPS (no Domain, Path=/),
 * so a sibling host such as the API host can neither read nor overwrite it.
 * Plain `http://localhost` development cannot use the prefix.
 */
export function sessionCookieName(secure: boolean): string {
  return secure ? '__Host-scanova-session' : 'scanova-session';
}

/**
 * httpOnly, SameSite=Strict, Secure on HTTPS. The member portal keeps the
 * session across browser restarts until the refresh token expires; the admin
 * console uses a browser-session cookie (gone when the browser closes) and an
 * absolute lifetime enforced on every request.
 */
export function sessionCookieAttributes(
  payload: SessionPayload,
  surface: Surface,
  secure: boolean,
  now: number,
): CookieAttributes {
  const base = { httpOnly: true, secure, sameSite: 'strict', path: '/' } as const;
  if (surface === 'admin') {
    return base;
  }
  return { ...base, maxAge: Math.max(0, Math.floor((sessionDeadline(payload) - now) / 1000)) };
}

export function clearedCookieAttributes(secure: boolean): CookieAttributes {
  return { httpOnly: true, secure, sameSite: 'strict', path: '/', maxAge: 0 };
}
