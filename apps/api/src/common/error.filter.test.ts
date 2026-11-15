import { BadRequestException, NotFoundException, PayloadTooLargeException } from '@nestjs/common';
import { ERROR_DEFINITIONS, type ErrorCode } from '@scanova/contracts/errors';
import { KeyringError } from '@scanova/keyring/errors';
import { KmsError } from '@scanova/kms/errors';
import { StorageError } from '@scanova/storage/errors';
import type { ArgumentsHost } from '@nestjs/common';
import { describe, expect, it } from 'vitest';
import { AppLogger } from '@scanova/config/logger';
import { AppError } from './app-error.js';
import { decodeCursor, encodeCursor } from './cursor.js';
import { ErrorFilter, toAppError } from './error.filter.js';
import { z } from 'zod';

function respond(exception: unknown): {
  status: number;
  body: unknown;
  headers: Record<string, string>;
  logs: string[];
} {
  const logs: string[] = [];
  const captured = { status: 0, body: undefined as unknown, headers: {} as Record<string, string> };
  const reply = {
    sent: false,
    header(name: string, value: string) {
      captured.headers[name] = value;
      return reply;
    },
    status(status: number) {
      captured.status = status;
      return reply;
    },
    send(body: unknown) {
      captured.body = body;
      return reply;
    },
  };
  const host = {
    switchToHttp: () => ({ getRequest: () => ({ id: 'req-1' }), getResponse: () => reply }),
  } as unknown as ArgumentsHost;
  new ErrorFilter(new AppLogger('debug', 'test', (line) => logs.push(line))).catch(exception, host);
  return { ...captured, logs };
}

describe('error mapping (§7.5)', () => {
  it('keeps the code, status, message, details and headers of an AppError', () => {
    for (const code of Object.keys(ERROR_DEFINITIONS) as ErrorCode[]) {
      const result = respond(new AppError(code));
      expect(result.status).toBe(ERROR_DEFINITIONS[code].status);
      expect(result.body).toEqual({
        data: null,
        error: { code, message: ERROR_DEFINITIONS[code].message },
        meta: { nextCursor: null },
      });
    }
    const limited = respond(new AppError('RATE_LIMITED', { headers: { 'retry-after': '42' } }));
    expect(limited.headers).toEqual({ 'retry-after': '42' });
    const invalid = respond(
      new AppError('VALIDATION_FAILED', { details: [{ field: 'title', message: 'Too short' }] }),
    );
    expect(invalid.body).toMatchObject({
      error: { details: [{ field: 'title', message: 'Too short' }] },
    });
  });

  it('maps infrastructure failures to SERVICE_UNAVAILABLE and unknown ones to INTERNAL_ERROR', () => {
    expect(toAppError(new KmsError('UNAVAILABLE', 'down')).code).toBe('SERVICE_UNAVAILABLE');
    expect(toAppError(new StorageError('UNAVAILABLE', 'down')).code).toBe('SERVICE_UNAVAILABLE');
    expect(toAppError(Object.assign(new Error('connect'), { code: 'ECONNREFUSED' })).code).toBe(
      'SERVICE_UNAVAILABLE',
    );
    expect(toAppError(Object.assign(new Error('prisma'), { code: 'P1001' })).code).toBe(
      'SERVICE_UNAVAILABLE',
    );
    expect(
      toAppError(new Error('wrapped', { cause: Object.assign(new Error('pg'), { code: '57P01' }) }))
        .code,
    ).toBe('SERVICE_UNAVAILABLE');
    expect(toAppError(new KmsError('PERMISSION_DENIED', 'no')).code).toBe('INTERNAL_ERROR');
    expect(toAppError(new StorageError('NOT_FOUND', 'gone')).code).toBe('NOT_FOUND');
    expect(toAppError(new KeyringError('KEY_DESTROYED', 'gone')).code).toBe('NOT_FOUND');
    expect(toAppError(new KeyringError('INVALID_STATE', 'odd')).code).toBe('INTERNAL_ERROR');
    expect(toAppError(new TypeError('bug')).code).toBe('INTERNAL_ERROR');
    expect(toAppError('a string').code).toBe('INTERNAL_ERROR');
  });

  it('maps framework and HTTP-layer errors by status', () => {
    expect(toAppError(new NotFoundException()).code).toBe('NOT_FOUND');
    expect(toAppError(new BadRequestException()).code).toBe('VALIDATION_FAILED');
    expect(toAppError(new PayloadTooLargeException()).code).toBe('FILE_TOO_LARGE');
    expect(
      toAppError(
        Object.assign(new Error('Unsupported Media Type'), {
          statusCode: 415,
          code: 'FST_ERR_CTP_INVALID_MEDIA_TYPE',
        }),
      ).code,
    ).toBe('UNSUPPORTED_MEDIA_TYPE');
    expect(toAppError(Object.assign(new Error('bad json'), { statusCode: 400 })).code).toBe(
      'VALIDATION_FAILED',
    );
    expect(toAppError(Object.assign(new Error('teapot'), { statusCode: 418 })).code).toBe(
      'INTERNAL_ERROR',
    );
  });

  it('never shows the message of an unexpected error, and logs it without the message', () => {
    const result = respond(new Error('SELECT * FROM users WHERE email = secret@example.org'));
    expect(result.status).toBe(500);
    expect(result.body).toEqual({
      data: null,
      error: { code: 'INTERNAL_ERROR', message: 'Something went wrong. Try again.' },
      meta: { nextCursor: null },
    });
    expect(result.logs).toHaveLength(1);
    expect(result.logs[0]).toContain('"event":"request.failed"');
    expect(result.logs[0]).toContain('"code":"INTERNAL_ERROR"');
    expect(result.logs[0]).not.toContain('secret@example.org');
    // Client errors are not logged as failures.
    expect(respond(new AppError('NOT_FOUND')).logs).toEqual([]);
  });
});

describe('cursors', () => {
  const Schema = z.object({ u: z.iso.datetime(), i: z.uuid() });

  it('round-trips and rejects anything a client made up', () => {
    const value = { u: '2026-11-15T12:00:00.000Z', i: '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f' };
    expect(decodeCursor(encodeCursor(value), Schema)).toEqual(value);
    for (const bad of [
      '',
      'not-base64-json',
      encodeCursor({ u: 'yesterday', i: 'x' }),
      encodeCursor([1, 2]),
    ]) {
      expect(() => decodeCursor(bad, Schema)).toThrow(AppError);
    }
  });
});
