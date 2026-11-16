import 'server-only';
import { notFound, redirect } from 'next/navigation';
import { PATHS, type Surface } from '@/lib/surface';
import type { ApiFailure, ApiResult } from './api/client';

/** Answers that mean "this session no longer works": the browser must sign in again. */
export const SESSION_ENDED_CODES: ReadonlySet<string> = new Set([
  'UNAUTHENTICATED',
  'SESSION_REVOKED',
  'ACCOUNT_DISABLED',
  'INVALID_REFRESH_TOKEN',
]);

/** Thrown for an API failure a page cannot recover from; error.tsx shows a retry. */
export class ApiRequestError extends Error {
  public constructor(public readonly failure: ApiFailure) {
    super(`API request failed: ${failure.code}`);
    this.name = 'ApiRequestError';
  }
}

/**
 * For server components: the data, or the right navigation for the failure.
 * A dead session goes through the expired-session route (which clears the cookie),
 * a pending password change to the password page, NOT_FOUND to the 404 page.
 */
export function unwrap<T>(result: ApiResult<T>, surface: Surface): T {
  if (result.ok) {
    return result.data;
  }
  handleSessionFailure(result, surface);
  if (result.code === 'NOT_FOUND') {
    notFound();
  }
  throw new ApiRequestError(result);
}

/** Redirects (and so never returns) when the failure means the session cannot go on. */
export function handleSessionFailure(failure: ApiFailure, surface: Surface): void {
  if (SESSION_ENDED_CODES.has(failure.code)) {
    redirect(PATHS[surface].expired);
  }
  if (failure.code === 'PASSWORD_CHANGE_REQUIRED') {
    redirect(PATHS[surface].password);
  }
}
