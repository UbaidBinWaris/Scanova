import { Catch, HttpException, type ArgumentsHost, type ExceptionFilter } from '@nestjs/common';
import { errorEnvelope } from '@scanova/contracts/envelope';
import { ERROR_DEFINITIONS, type ErrorCode } from '@scanova/contracts/errors';
import { KeyringError } from '@scanova/keyring/errors';
import { KmsError } from '@scanova/kms/errors';
import { StorageError } from '@scanova/storage/errors';
import type { FastifyReply, FastifyRequest } from 'fastify';
import type { AppLogger } from '@scanova/config/logger';
import { AppError } from './app-error.js';

const STATUS_TO_CODE: Readonly<Record<number, ErrorCode>> = {
  400: 'VALIDATION_FAILED',
  401: 'UNAUTHENTICATED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'NOT_FOUND',
  409: 'ID_CONFLICT',
  413: 'FILE_TOO_LARGE',
  415: 'UNSUPPORTED_MEDIA_TYPE',
  422: 'CHECKSUM_MISMATCH',
  429: 'RATE_LIMITED',
  503: 'SERVICE_UNAVAILABLE',
};

// Database driver and Prisma codes that mean "cannot reach the database".
const DATABASE_UNAVAILABLE_CODES = new Set([
  'ECONNREFUSED',
  'ECONNRESET',
  'ETIMEDOUT',
  'ENOTFOUND',
  'P1001',
  'P1002',
  'P1008',
  'P1017',
  '57P01',
  '57P03',
  '08000',
  '08003',
  '08006',
]);

/**
 * Translates whatever escaped a request into the error table of §7.5 — the one
 * place where that mapping exists. Typed errors keep their code; anything
 * unknown becomes `INTERNAL_ERROR` and its details go to the server log only.
 */
export function toAppError(exception: unknown): AppError {
  if (exception instanceof AppError) {
    return exception;
  }
  if (exception instanceof KmsError) {
    return exception.code === 'UNAVAILABLE'
      ? new AppError('SERVICE_UNAVAILABLE', { cause: exception })
      : new AppError('INTERNAL_ERROR', { cause: exception });
  }
  if (exception instanceof StorageError) {
    return exception.code === 'UNAVAILABLE'
      ? new AppError('SERVICE_UNAVAILABLE', { cause: exception })
      : new AppError('NOT_FOUND', { cause: exception });
  }
  if (exception instanceof KeyringError) {
    // A key that is gone means the data it protected is gone for this caller.
    return exception.code === 'KEY_NOT_FOUND' || exception.code === 'KEY_DESTROYED'
      ? new AppError('NOT_FOUND', { cause: exception })
      : new AppError('INTERNAL_ERROR', { cause: exception });
  }
  if (exception instanceof HttpException) {
    const code = STATUS_TO_CODE[exception.getStatus()];
    return new AppError(code ?? 'INTERNAL_ERROR', { cause: exception });
  }
  // Errors raised by the HTTP layer itself (malformed JSON, unknown content type, body limit).
  const candidate = exception as {
    statusCode?: unknown;
    code?: unknown;
    cause?: { code?: unknown };
  };
  if (
    typeof candidate.statusCode === 'number' &&
    candidate.statusCode >= 400 &&
    candidate.statusCode < 500
  ) {
    const code = STATUS_TO_CODE[candidate.statusCode];
    if (code !== undefined) {
      return new AppError(code, { cause: exception });
    }
  }
  for (const code of [candidate.code, candidate.cause?.code]) {
    if (typeof code === 'string' && DATABASE_UNAVAILABLE_CODES.has(code)) {
      return new AppError('SERVICE_UNAVAILABLE', { cause: exception });
    }
  }
  return new AppError('INTERNAL_ERROR', { cause: exception });
}

@Catch()
export class ErrorFilter implements ExceptionFilter {
  public constructor(private readonly logger: AppLogger) {}

  public catch(exception: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const request = http.getRequest<FastifyRequest>();
    const reply = http.getResponse<FastifyReply>();
    const error = toAppError(exception);

    if (error.status >= 500) {
      this.logger.failure('request.failed', error.cause ?? error, {
        requestId: request.id,
        code: error.code,
        userId: request.auth?.userPublicId,
      });
    }
    if (reply.sent) {
      // The response already started (a download that failed mid-stream): the
      // connection is torn down so the client sees an incomplete body.
      reply.raw.destroy();
      return;
    }
    for (const [name, value] of Object.entries(error.headers)) {
      void reply.header(name, value);
    }
    const isInternal = error.code === 'INTERNAL_ERROR';
    void reply.status(error.status).send(
      errorEnvelope({
        code: error.code,
        // Never the message of an unexpected error: it could quote input or internals.
        message: isInternal ? ERROR_DEFINITIONS.INTERNAL_ERROR.message : error.message,
        ...(error.details === undefined || isInternal ? {} : { details: error.details }),
      }),
    );
  }
}
