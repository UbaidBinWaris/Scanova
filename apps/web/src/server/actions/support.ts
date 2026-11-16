import 'server-only';
import type { z } from 'zod';
import type { ActionResult } from '@/lib/action-result';
import type { Surface } from '@/lib/surface';
import type { ApiFailure } from '../api/client';
import { handleSessionFailure } from '../guard';
import { getLogger } from '../logger';

/** The browser gets the API's user-facing message; a dead session navigates to sign-in. */
export function fromApiFailure(
  failure: ApiFailure,
  surface: Surface,
  action: string,
): ActionResult<never> {
  handleSessionFailure(failure, surface);
  if (failure.status >= 500 || failure.code === 'BAD_RESPONSE') {
    getLogger().event('warn', 'action.api_failed', {
      action,
      code: failure.code,
      status: failure.status,
    });
  }
  if (failure.code === 'RATE_LIMITED' && failure.retryAfterSeconds !== undefined) {
    return {
      ok: false,
      code: failure.code,
      message: `Too many attempts. Try again in ${failure.retryAfterSeconds} seconds.`,
    };
  }
  return { ok: false, code: failure.code, message: failure.message };
}

/** Field messages from a failed Zod validation, first message per field. */
export function invalid(error: z.ZodError): ActionResult<never> {
  const fieldErrors: Record<string, string> = {};
  for (const issue of error.issues) {
    const field = issue.path.map(String).join('.') || 'form';
    fieldErrors[field] ??= issue.message;
  }
  return {
    ok: false,
    code: 'VALIDATION_FAILED',
    message: 'Check the highlighted fields.',
    fieldErrors,
  };
}

export const ok = <T>(data: T): ActionResult<T> => ({ ok: true, data });
