import { ERROR_DEFINITIONS, type ErrorCode } from '@scanova/contracts/errors';

export interface AppErrorOptions {
  /** Overrides the default user-facing message of the code. */
  readonly message?: string;
  /** Machine-readable extras, e.g. the invalid fields of a validation error. */
  readonly details?: unknown;
  /** Extra response headers, e.g. `Retry-After`. */
  readonly headers?: Readonly<Record<string, string>>;
  readonly cause?: unknown;
}

/**
 * The one error type services throw for anything a client should hear about.
 * It names a code from §7.5; the status and the default message come from the
 * contract. Everything else that escapes a request becomes `INTERNAL_ERROR`.
 */
export class AppError extends Error {
  public readonly code: ErrorCode;
  public readonly status: number;
  public readonly details: unknown;
  public readonly headers: Readonly<Record<string, string>>;

  public constructor(code: ErrorCode, options: AppErrorOptions = {}) {
    super(options.message ?? ERROR_DEFINITIONS[code].message, { cause: options.cause });
    this.name = 'AppError';
    this.code = code;
    this.status = ERROR_DEFINITIONS[code].status;
    this.details = options.details;
    this.headers = options.headers ?? {};
  }
}

export function notFound(): AppError {
  return new AppError('NOT_FOUND');
}
