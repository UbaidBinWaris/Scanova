import { z } from 'zod';

/**
 * Every error the API can answer with (§7.5). The HTTP status and the default
 * user-facing message live here so the mapping exists in exactly one place.
 */
export const ERROR_DEFINITIONS = {
  VALIDATION_FAILED: { status: 400, message: 'The request is not valid.' },
  UNAUTHENTICATED: { status: 401, message: 'Sign in to continue.' },
  INVALID_CREDENTIALS: { status: 401, message: 'E-mail or password is incorrect.' },
  INVALID_REFRESH_TOKEN: { status: 401, message: 'The session has expired. Sign in again.' },
  SESSION_REVOKED: { status: 401, message: 'This session was signed out. Sign in again.' },
  ACCOUNT_DISABLED: { status: 403, message: 'This account has been disabled.' },
  ADMIN_WEB_ONLY: { status: 403, message: 'Administrators sign in on the web console.' },
  PASSWORD_CHANGE_REQUIRED: {
    status: 403,
    message: 'Change your temporary password to continue.',
  },
  FORBIDDEN: { status: 403, message: 'You are not allowed to do this.' },
  NOT_FOUND: { status: 404, message: 'Not found.' },
  ID_CONFLICT: { status: 409, message: 'This id is already in use.' },
  FILE_TOO_LARGE: { status: 413, message: 'The file is too large.' },
  QUOTA_EXCEEDED: { status: 413, message: 'Not enough storage left for this file.' },
  UNSUPPORTED_MEDIA_TYPE: { status: 415, message: 'Only PDF files are accepted.' },
  CHECKSUM_MISMATCH: { status: 422, message: 'The upload was corrupted. Try again.' },
  RATE_LIMITED: { status: 429, message: 'Too many attempts. Try again shortly.' },
  INTERNAL_ERROR: { status: 500, message: 'Something went wrong. Try again.' },
  SERVICE_UNAVAILABLE: { status: 503, message: 'The server is temporarily unavailable.' },
} as const satisfies Record<string, { status: number; message: string }>;

export type ErrorCode = keyof typeof ERROR_DEFINITIONS;

export const ERROR_CODES = Object.keys(ERROR_DEFINITIONS) as [ErrorCode, ...ErrorCode[]];

export const ErrorCodeSchema = z.enum(ERROR_CODES);

/** One invalid field of a `VALIDATION_FAILED` answer. */
export const ValidationDetailSchema = z.object({
  field: z.string(),
  message: z.string(),
});
export type ValidationDetail = z.infer<typeof ValidationDetailSchema>;

export const ErrorBodySchema = z.object({
  code: ErrorCodeSchema,
  message: z.string(),
  details: z.unknown().optional(),
});
export type ErrorBody = z.infer<typeof ErrorBodySchema>;
