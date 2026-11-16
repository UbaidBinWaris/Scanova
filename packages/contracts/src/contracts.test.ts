import { describe, expect, it } from 'vitest';
import {
  AdminUpdateUserRequestSchema,
  AuditEventQuerySchema,
  CreateRotationRequestSchema,
} from './admin.js';
import { LoginRequestSchema } from './auth.js';
import { PageQuerySchema, UuidSchema } from './common.js';
import {
  CreateDocumentRequestSchema,
  DocumentPageSchema,
  MAX_CLIENT_OCR_TEXT_BYTES,
  MAX_PAGE_EDIT_BYTES,
  ReplacePagesRequestSchema,
  UpdateDocumentRequestSchema,
  UploadHeadersSchema,
} from './documents.js';
import { ErrorEnvelopeSchema, errorEnvelope, successEnvelope } from './envelope.js';
import { ERROR_CODES, ERROR_DEFINITIONS } from './errors.js';
import { JOB_NAMES, JOB_PAYLOAD_SCHEMAS } from './jobs.js';

const ID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f';
const OTHER_ID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e50';

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
      AI_NOT_READY: 409,
      FILE_TOO_LARGE: 413,
      QUOTA_EXCEEDED: 413,
      UNSUPPORTED_MEDIA_TYPE: 415,
      CHECKSUM_MISMATCH: 422,
      RATE_LIMITED: 429,
      INTERNAL_ERROR: 500,
      SERVICE_UNAVAILABLE: 503,
      NOT_ENOUGH_MEMORY: 503,
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

  it('accepts a page list with recipes as the phone writes them, unknown fields included', () => {
    const phoneRecipe = {
      v: 1,
      sourceRotation: 90,
      crop: [
        { x: 0.1, y: 0.1 },
        { x: 0.9, y: 0.1 },
        { x: 0.9, y: 0.9 },
        { x: 0.1, y: 0.9 },
      ],
      rotation: 0,
      filter: 'enhanced',
      filterStrength: 1,
      brightness: 0,
      contrast: 0.2,
      markup: [{ tool: 'pen', color: 0xff1e5eff, width: 0.01, points: [{ x: 0.5, y: 0.5 }] }],
      // A 45 × 31 mesh as the phone writes it (here all points at 0,0).
      flatten: { rows: 45, cols: 31, points: Buffer.alloc(45 * 31 * 4).toString('base64') },
      layout: 'id_card',
      shadowRemoval: true,
    };
    const parsed = ReplacePagesRequestSchema.parse({
      pages: [
        { id: ID.toUpperCase(), position: 0, width: 1240, height: 1754, edit: phoneRecipe },
        { id: OTHER_ID, position: 3, width: 1240, height: 1754 },
      ],
    });
    expect(parsed.pages[0]).toEqual({
      id: ID,
      position: 0,
      width: 1240,
      height: 1754,
      edit: phoneRecipe,
    });
    expect(parsed.pages[1]!.edit).toBeUndefined();
    expect(DocumentPageSchema.shape.edit.parse(phoneRecipe)).toEqual(phoneRecipe);
  });

  it('refuses invalid page lists', () => {
    const page = { id: ID, position: 0, width: 10, height: 10, edit: { v: 1 } };
    const invalid = [
      { pages: [] },
      { pages: [page, { ...page, position: 1 }] },
      { pages: [page, { ...page, id: OTHER_ID }] },
      { pages: [{ ...page, width: 0 }] },
      { pages: [{ ...page, edit: { rotation: 90 } }] },
      { pages: [{ ...page, edit: { v: 1, crop: [{ x: 0, y: 0 }] } }] },
      { pages: [{ ...page, edit: { v: 1, filter: 7 } }] },
      { pages: [{ ...page, edit: { v: 1, padding: 'x'.repeat(MAX_PAGE_EDIT_BYTES) } }] },
      { pages: [{ ...page, edit: { v: 1, flatten: { rows: 1, cols: 31, points: '' } } }] },
      { pages: [{ ...page, edit: { v: 1, flatten: { rows: 45, cols: 65, points: '' } } }] },
      {
        pages: [
          { ...page, edit: { v: 1, flatten: { rows: 45, cols: 31, points: 'not base64!' } } },
        ],
      },
      {
        pages: [
          { ...page, edit: { v: 1, flatten: { rows: 45, cols: 31, points: 'A'.repeat(21_852) } } },
        ],
      },
      // Valid Base64, but not 45 × 31 points.
      { pages: [{ ...page, edit: { v: 1, flatten: { rows: 45, cols: 31, points: 'AAAA' } } }] },
      { pages: [{ ...page, edit: { v: 1, layout: 'x'.repeat(33) } }] },
    ];
    for (const request of invalid) {
      expect(ReplacePagesRequestSchema.safeParse(request).success).toBe(false);
    }
    expect(ReplacePagesRequestSchema.safeParse({ pages: [page] }).success).toBe(true);
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

  it('lets an admin set or remove a storage quota, and nothing else alone is empty', () => {
    expect(
      AdminUpdateUserRequestSchema.safeParse({ storageQuotaBytes: 5_000_000_000 }).success,
    ).toBe(true);
    expect(AdminUpdateUserRequestSchema.safeParse({ storageQuotaBytes: null }).success).toBe(true);
    expect(AdminUpdateUserRequestSchema.safeParse({ storageQuotaBytes: -1 }).success).toBe(false);
    expect(AdminUpdateUserRequestSchema.safeParse({ storageQuotaBytes: 1.5 }).success).toBe(false);
    expect(AdminUpdateUserRequestSchema.safeParse({}).success).toBe(false);
  });

  it('filters the audit log by action name and actor id', () => {
    expect(
      AuditEventQuerySchema.parse({ action: 'LOGIN_FAILED', actorId: ID.toUpperCase() }),
    ).toEqual({ limit: 50, action: 'LOGIN_FAILED', actorId: ID });
    expect(AuditEventQuerySchema.safeParse({ action: 'login_failed' }).success).toBe(false);
    expect(AuditEventQuerySchema.safeParse({ action: 'X'.repeat(65) }).success).toBe(false);
    expect(AuditEventQuerySchema.safeParse({ actorId: 'not-a-uuid' }).success).toBe(false);
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
