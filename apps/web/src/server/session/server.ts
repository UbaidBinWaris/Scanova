import 'server-only';
import { cookies } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { PATHS, userTypeFits, type Surface } from '@/lib/surface';
import type { CallContext } from '../api/client';
import { getWebConfig } from '../config';
import { requestInfo, type RequestInfo } from '../request';
import { clearedCookieAttributes, sessionCookieAttributes, sessionCookieName } from './cookie';
import { sessionDeadline, type SessionPayload } from './payload';
import { createSealer, type Sealer } from './seal';

let sealer: Sealer | null = null;

export function getSealer(): Sealer {
  sealer ??= createSealer(getWebConfig().cookieSecret);
  return sealer;
}

/** The session of this request, if its cookie is valid for this host and not past its deadline. */
export async function readSession(info?: RequestInfo): Promise<SessionPayload | null> {
  const request = info ?? (await requestInfo());
  const value = (await cookies()).get(sessionCookieName(request.secure))?.value;
  if (value === undefined) {
    return null;
  }
  const payload = getSealer().unseal(value, request.surface);
  return payload !== null && sessionDeadline(payload) > Date.now() ? payload : null;
}

export interface SignedInRequest {
  readonly info: RequestInfo;
  readonly session: SessionPayload;
  /** What API calls for this user carry. */
  readonly context: CallContext;
}

/**
 * The gate every protected page, route handler and server action passes. The
 * proxy has already refreshed the access token; this checks the surface, the
 * user type and a pending password change. (The API enforces all of it again.)
 */
export async function requireSession(
  surface: Surface,
  options: { allowPasswordChange?: boolean } = {},
): Promise<SignedInRequest> {
  const info = await requestInfo();
  if (info.surface !== surface) {
    notFound();
  }
  const paths = PATHS[surface];
  const session = await readSession(info);
  if (session === null) {
    redirect(paths.signIn);
  }
  if (!userTypeFits(session.userType, surface)) {
    redirect(paths.expired);
  }
  if (session.mustChangePassword && options.allowPasswordChange !== true) {
    redirect(paths.password);
  }
  return {
    info,
    session,
    context: { accessToken: session.accessToken, clientIp: info.clientIp },
  };
}

/** Only in server actions and route handlers: Next.js cannot set cookies while rendering. */
export async function writeSession(payload: SessionPayload, info: RequestInfo): Promise<void> {
  (await cookies()).set(
    sessionCookieName(info.secure),
    getSealer().seal(payload),
    sessionCookieAttributes(payload, info.surface, info.secure, Date.now()),
  );
}

export async function clearSession(info: RequestInfo): Promise<void> {
  (await cookies()).set(sessionCookieName(info.secure), '', clearedCookieAttributes(info.secure));
}
