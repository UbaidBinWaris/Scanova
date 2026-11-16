import 'server-only';
import { NextResponse } from 'next/server';
import { userTypeFits, type Surface } from '@/lib/surface';
import type { ApiResult } from './api/client';
import { SESSION_ENDED_CODES } from './guard';
import { requestInfo } from './request';
import { readSession, type SignedInRequest } from './session/server';

const NO_STORE = { 'cache-control': 'private, no-store' };

export function errorJson(status: number, code: string, message: string): NextResponse {
  return NextResponse.json(
    { data: null, error: { code, message }, meta: { nextCursor: null } },
    { status, headers: NO_STORE },
  );
}

/**
 * For route handlers (JSON and file downloads): the signed-in request, or the
 * response to send instead (401 JSON — a fetch must not be redirected to HTML).
 */
export async function sessionForRoute(surface: Surface): Promise<SignedInRequest | NextResponse> {
  const info = await requestInfo();
  if (info.surface !== surface) {
    return errorJson(404, 'NOT_FOUND', 'Not found.');
  }
  const session = await readSession(info);
  if (session === null || !userTypeFits(session.userType, surface)) {
    return errorJson(401, 'UNAUTHENTICATED', 'Sign in to continue.');
  }
  if (session.mustChangePassword) {
    return errorJson(
      403,
      'PASSWORD_CHANGE_REQUIRED',
      'Change your temporary password to continue.',
    );
  }
  return { info, session, context: { accessToken: session.accessToken, clientIp: info.clientIp } };
}

/** Re-emits an API answer in the same envelope, so the browser validates it with the same schemas. */
export function envelopeJson<T>(result: ApiResult<T>): NextResponse {
  if (result.ok) {
    return NextResponse.json(
      { data: result.data, error: null, meta: { nextCursor: result.nextCursor } },
      { headers: NO_STORE },
    );
  }
  if (SESSION_ENDED_CODES.has(result.code)) {
    return errorJson(401, result.code, result.message);
  }
  // The two codes only the web server sees are reported as their API equivalents.
  if (result.code === 'NETWORK_ERROR') {
    return errorJson(503, 'SERVICE_UNAVAILABLE', result.message);
  }
  if (result.code === 'BAD_RESPONSE') {
    return errorJson(502, 'INTERNAL_ERROR', result.message);
  }
  return errorJson(result.status, result.code, result.message);
}
