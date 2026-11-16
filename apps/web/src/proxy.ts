import { randomBytes } from 'node:crypto';
import { NextResponse, type NextRequest } from 'next/server';
import { buildContentSecurityPolicy } from '@/lib/csp';
import { isSafeMethod, isSameOriginRequest } from '@/lib/csrf';
import { classifyRequest } from '@/lib/routing';
import { originFor, PATHS, surfaceForHost } from '@/lib/surface';
import { ApiClient } from '@/server/api/client';
import { scanovaApi, type ScanovaApi } from '@/server/api/scanova';
import { clientIpFrom } from '@/server/client-ip';
import { getWebConfig } from '@/server/config';
import { getLogger } from '@/server/logger';
import { SECURITY_HEADERS } from '@/server/security-headers';
import {
  clearedCookieAttributes,
  sessionCookieAttributes,
  sessionCookieName,
} from '@/server/session/cookie';
import { evaluateSession } from '@/server/session/evaluate';
import { RefreshCoordinator, type RefreshOutcome } from '@/server/session/refresh';
import { createSealer, type Sealer } from '@/server/session/seal';

interface ProxyState {
  readonly api: ScanovaApi;
  readonly sealer: Sealer;
  readonly refresher: RefreshCoordinator;
}

let state: ProxyState | null = null;

function proxyState(): ProxyState {
  if (state === null) {
    const web = getWebConfig();
    const api = scanovaApi(new ApiClient({ apiUrl: web.apiUrl, serviceToken: web.serviceToken }));
    const refresher = new RefreshCoordinator(async (refreshToken, clientIp) => {
      const result = await api.auth.refresh({ clientIp }, refreshToken);
      if (result.ok) {
        return { kind: 'refreshed', tokens: result.data } satisfies RefreshOutcome;
      }
      if (result.code === 'NETWORK_ERROR' || result.status >= 500 || result.status === 429) {
        getLogger().event('warn', 'session.refresh_unavailable', { code: result.code });
        return { kind: 'unavailable' };
      }
      return { kind: 'rejected', code: result.code };
    });
    state = { api, sealer: createSealer(web.cookieSecret), refresher };
  }
  return state;
}

function plain(status: number, text: string): NextResponse {
  return new NextResponse(text, {
    status,
    headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' },
  });
}

function secure(response: NextResponse, csp: string | null): NextResponse {
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
    response.headers.set(name, value);
  }
  response.headers.set(
    'content-security-policy',
    csp ?? "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  );
  return response;
}

/**
 * Runs before every request (Next.js "proxy", Node.js runtime):
 *
 * 1. Host routing: the member portal (/app) only on the site host, the admin
 *    console (/admin) only on the admin host.
 * 2. CSRF: a state-changing request must come from this surface's own origin.
 * 3. Session: the encrypted cookie is checked and, shortly before the access
 *    token expires, renewed with the refresh token (once, however many requests
 *    race). The new cookie goes to the browser and to the app for this request.
 * 4. A fresh CSP nonce, which Next.js applies to every script it renders.
 *
 * Authorisation is not left to this layer alone: every page, route handler and
 * server action checks the session again, and the API checks the token.
 */
export async function proxy(request: NextRequest): Promise<NextResponse> {
  const web = getWebConfig();
  const surface = surfaceForHost(request.headers.get('host'), web);
  const decision = classifyRequest(surface, request.nextUrl.pathname);
  if (decision.kind === 'health') {
    return NextResponse.next();
  }
  if (decision.kind === 'not-found') {
    return secure(plain(404, 'Not found'), null);
  }
  const origin = originFor(surface, web);
  if (decision.kind === 'redirect') {
    return secure(NextResponse.redirect(new URL(decision.location, origin), 307), null);
  }
  if (!isSafeMethod(request.method) && !isSameOriginRequest(request.headers, origin)) {
    getLogger().event('warn', 'request.cross_origin_refused', { surface });
    return secure(plain(403, 'Forbidden'), null);
  }

  const https = origin.startsWith('https:');
  const cookieName = sessionCookieName(https);
  const clientIp = clientIpFrom(request.headers.get('x-forwarded-for'));
  const { api, sealer, refresher } = proxyState();
  const now = Date.now();
  const session = await evaluateSession(request.cookies.get(cookieName)?.value, surface, clientIp, {
    sealer,
    refresh: (token, ip) => refresher.refresh(token, ip),
    now: () => now,
  });

  let cookieUpdate: {
    value: string;
    attributes: ReturnType<typeof clearedCookieAttributes>;
  } | null = null;
  switch (session.kind) {
    case 'refreshed':
      request.cookies.set(cookieName, session.cookie);
      cookieUpdate = {
        value: session.cookie,
        attributes: sessionCookieAttributes(session.payload, surface, https, now),
      };
      break;
    case 'ended':
      if (session.reason === 'lifetime') {
        // Past the admin console's absolute lifetime: end it at the API too.
        void api.auth.logout({ clientIp }, session.payload.refreshToken).catch(() => undefined);
      }
      request.cookies.delete(cookieName);
      cookieUpdate = { value: '', attributes: clearedCookieAttributes(https) };
      break;
    case 'none':
      if (session.clearCookie) {
        request.cookies.delete(cookieName);
        cookieUpdate = { value: '', attributes: clearedCookieAttributes(https) };
      }
      break;
    case 'active':
    case 'degraded':
      break;
  }
  const signedIn =
    session.kind === 'active' || session.kind === 'refreshed' || session.kind === 'degraded';

  const nonce = randomBytes(16).toString('base64');
  const csp = buildContentSecurityPolicy({
    nonce,
    development: web.runtime.nodeEnv === 'development',
    https,
  });

  let response: NextResponse;
  if (!signedIn && decision.kind === 'protected-page') {
    const target = new URL(PATHS[surface].signIn, origin);
    if (request.method === 'GET' && request.nextUrl.pathname !== PATHS[surface].home) {
      target.searchParams.set('next', request.nextUrl.pathname);
    }
    if (session.kind === 'ended') {
      target.searchParams.set('expired', '1');
    }
    response = NextResponse.redirect(target, 303);
  } else if (!signedIn && decision.kind === 'protected-data') {
    response = NextResponse.json(
      {
        data: null,
        error: { code: 'UNAUTHENTICATED', message: 'Sign in to continue.' },
        meta: { nextCursor: null },
      },
      { status: 401, headers: { 'cache-control': 'no-store' } },
    );
  } else {
    const headers = new Headers(request.headers);
    // Next.js reads the nonce from the request's CSP header and puts it on its scripts.
    headers.set('content-security-policy', csp);
    response = NextResponse.next({ request: { headers } });
  }

  if (cookieUpdate !== null) {
    response.cookies.set(cookieName, cookieUpdate.value, cookieUpdate.attributes);
  }
  return secure(response, csp);
}

export const config = {
  // Static build assets and pdf.js's files need no session, nonce or routing.
  matcher: ['/((?!_next/static/|favicon\\.svg|app/pdfjs/|healthz).*)'],
};
