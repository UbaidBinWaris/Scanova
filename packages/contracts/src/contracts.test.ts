import { describe, expect, it } from 'vitest';
import { CreateRotationRequestSchema } from './admin.js';
import { LoginRequestSchema } from './auth.js';
import { PageQuerySchema, UuidSchema } from './common.js';
import {
  CreateDocumentRequestSchema,
  MAX_CLIENT_OCR_TEXT_BYTES,
  UpdateDocumentRequestSchema,
  UploadHeadersSchema,
} from './documents.js';
import { ErrorEnvelopeSchema, errorEnvelope, successEnvelope } from './envelope.js';
import { ERROR_CODES, ERROR_DEFINITIONS } from './errors.js';
import { JOB_NAMES, JOB_PAYLOAD_SCHEMAS } from './jobs.js';

const ID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f';

describe('error codes', () => {
  it('matches the contract table exactly', () => {
    expect(
      Object.fromEntries(ERROR_CODES.map((code) => [code, ERROR_DEFINITIONS[code].status])),
    ).toEqual({
      VALIDATION_FAILED: 400,
      UNAUTHENTICATED: 401,
      INVALID_CREDENTIALS: 401,
      INVALID_REFRESH_TOKEN: 401,
      SESSION_REVOKED: 401,
      ACCOUNT_DISABLED: 403,
      ADMIN_WEB_ONLY: 403,
      PASSWORD_CHANGE_REQUIRED: 403,
      FORBIDDEN: 403,
      NOT_FOUND: 404,
      ID_CONFLICT: 409,
      FILE_TOO_LARGE: 413,
      QUOTA_EXCEEDED: 413,
      UNSUPPORTED_MEDIA_TYPE: 415,
      CHECKSUM_MISMATCH: 422,
      RATE_LIMITED: 429,
      INTERNAL_ERROR: 500,
      SERVICE_UNAVAILABLE: 503,
    });
  });
});

describe('envelope', () => {
  it('always carries data, error and meta', () => {
    expect(successEnvelope({ a: 1 })).toEqual({
      data: { a: 1 },
      error: null,
      meta: { nextCursor: null },
    });
    expect(successEnvelope([1], 'next')).toEqual({
      data: [1],
      error: null,
      meta: { nextCursor: 'next' },
    });
    const failure = errorEnvelope({ code: 'NOT_FOUND', message: 'Not found.' });
    expect(failure).toEqual({
      data: null,
      error: { code: 'NOT_FOUND', message: 'Not found.' },
      meta: { nextCursor: null },
    });
    expect(ErrorEnvelopeSchema.safeParse(failure).success).toBe(true);
    expect(
      ErrorEnvelopeSchema.safeParse({ data: null, error: { code: 'NOT_FOUND', message: 'x' } })
        .success,
    ).toBe(true);
  });
});

describe('request schemas', () => {
  it('accepts the documented mobile login and rejects other clients', () => {
    const body = {
      email: '  Member@Example.org ',
      password: 'correct horse battery',
      client: 'mobile',
      device: { name: 'Pixel', platform: 'android', model: 'Pixel 9', appVersion: '1.0.0' },
    };
    const parsed = LoginRequestSchema.parse(body);
    expect(parsed.email).toBe('Member@Example.org');
    expect(LoginRequestSchema.safeParse({ ...body, client: 'web' }).success).toBe(false);
    expect(LoginRequestSchema.safeParse({ ...body, email: 'not-an-email' }).success).toBe(false);
  });

  it('normalises ids to lower case and rejects non-UUIDs', () => {
    expect(UuidSchema.parse(ID.toUpperCase())).toBe(ID);
    expect(UuidSchema.safeParse('1234').success).toBe(false);
  });

  it('limits clientOcrText to 1 MiB of UTF-8', () => {
    const base = { id: ID, title: 'Invoice', pageCount: 2, capturedAt: '2026-11-15T10:00:00Z' };
    expect(CreateDocumentRequestSchema.safeParse(base).success).toBe(true);
    const atLimit = 'a'.repeat(MAX_CLIENT_OCR_TEXT_BYTES);
    expect(CreateDocumentRequestSchema.safeParse({ ...base, clientOcrText: atLimit }).success).toBe(
      true,
    );
    // Two bytes per character: half the characters already exceed the limit by one byte.
    const multiByte = 'é'.repeat(MAX_CLIENT_OCR_TEXT_BYTES / 2) + 'a';
    expect(
      CreateDocumentRequestSchema.safeParse({ ...base, clientOcrText: multiByte }).success,
    ).toBe(false);
  });

  it('requires at least one field when patching a document', () => {
    expect(UpdateDocumentRequestSchema.safeParse({}).success).toBe(false);
    expect(UpdateDocumentRequestSchema.safeParse({ tags: [] }).success).toBe(true);
  });

  it('parses upload headers and lower-cases the checksum', () => {
    const parsed = UploadHeadersSchema.parse({
      'content-length': '1024',
      'x-content-sha256': 'AB'.repeat(32),
    });
    expect(parsed).toEqual({ 'content-length': 1024, 'x-content-sha256': 'ab'.repeat(32) });
    expect(UploadHeadersSchema.safeParse({ 'content-length': '10' }).success).toBe(false);
  });

  it('coerces and bounds page queries', () => {
    expect(PageQuerySchema.parse({})).toEqual({ limit: 50 });
    expect(PageQuerySchema.parse({ limit: '10', cursor: 'abc' })).toEqual({
      limit: 10,
      cursor: 'abc',
    });
    expect(PageQuerySchema.safeParse({ limit: '0' }).success).toBe(false);
    expect(PageQuerySchema.safeParse({ limit: '201' }).success).toBe(false);
  });

  it('requires the scope-specific target for a rotation', () => {
    expect(CreateRotationRequestSchema.safeParse({ scope: 'JWT_SIGNING' }).success).toBe(true);
    expect(CreateRotationRequestSchema.safeParse({ scope: 'USER_TYPE_MASTER' }).success).toBe(
      false,
    );
    expect(
      CreateRotationRequestSchema.safeParse({ scope: 'USER_TYPE_MASTER', userType: 'MEMBER' })
        .success,
    ).toBe(true);
    expect(CreateRotationRequestSchema.safeParse({ scope: 'USER_KEY' }).success).toBe(false);
    expect(CreateRotationRequestSchema.safeParse({ scope: 'USER_KEY', userId: ID }).success).toBe(
      true,
    );
  });
});

describe('job payloads', () => {
  it('carry ids only and reject anything else', () => {
    const ocr = JOB_PAYLOAD_SCHEMAS[JOB_NAMES.documentOcr];
    expect(ocr.safeParse({ documentId: ID }).success).toBe(true);
    expect(ocr.safeParse({ documentId: ID, title: 'leak' }).success).toBe(false);
    expect(JOB_PAYLOAD_SCHEMAS[JOB_NAMES.trashPurge].safeParse({}).success).toBe(true);
    expect(JOB_PAYLOAD_SCHEMAS[JOB_NAMES.trashPurge].safeParse({ text: 'x' }).success).toBe(false);
  });
});
