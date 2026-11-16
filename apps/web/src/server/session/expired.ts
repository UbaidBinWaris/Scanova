import 'server-only';
import { NextResponse } from 'next/server';
import { PATHS, userTypeFits, type Surface } from '@/lib/surface';
import { getApi } from '../api/instance';
import { SESSION_ENDED_CODES } from '../guard';
import { requestInfo } from '../request';
import { clearSession, readSession } from './server';

/**
 * Where pages send the browser when the API says the session is over. The
 * cookie is removed only after the API confirms it (a cross-site link here
 * cannot sign anybody out), then the browser goes to sign-in.
 */
export async function handleExpiredSession(surface: Surface): Promise<Response> {
  const info = await requestInfo();
  if (info.surface !== surface) {
    return new NextResponse('Not found', { status: 404 });
  }
  const paths = PATHS[surface];
  const session = await readSession(info);
  if (session !== null && userTypeFits(session.userType, surface)) {
    const me = await getApi().me.get({ accessToken: session.accessToken, clientIp: info.clientIp });
    if (me.ok) {
      return NextResponse.redirect(new URL(paths.home, info.origin), 303);
    }
    if (!SESSION_ENDED_CODES.has(me.code)) {
      // The API is unreachable or failing: keep the session and say so.
      return new NextResponse('The server is not reachable right now. Try again shortly.', {
        status: 503,
        headers: { 'content-type': 'text/plain; charset=utf-8', 'retry-after': '10' },
      });
    }
  }
  await clearSession(info);
  return NextResponse.redirect(new URL(`${paths.signIn}?expired=1`, info.origin), 303);
}
