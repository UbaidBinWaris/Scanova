import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import {
  AdminCreateUserRequestSchema,
  AdminCreateUserResponseSchema,
  AdminResetPasswordResponseSchema,
  AdminUpdateUserRequestSchema,
  AuditEventListSchema,
  AuditEventQuerySchema,
  CreateRotationRequestSchema,
  KeyStatusSchema,
  RotationRunListSchema,
  RotationRunSchema,
  UserListSchema,
} from '@scanova/contracts/admin';
import { AiRuntimeStatusSchema, SetAiRuntimeRequestSchema } from '@scanova/contracts/ai-runtime';
import {
  LoginRequestSchema,
  RegisterRequestSchema,
  LogoutRequestSchema,
  RefreshRequestSchema,
  TokensSchema,
  UserSchema,
  WebLoginRequestSchema,
} from '@scanova/contracts/auth';
import { PageQuerySchema, SuccessSchema } from '@scanova/contracts/common';
import {
  CreateDocumentRequestSchema,
  DocumentListSchema,
  DocumentPageSchema,
  DocumentPagesSchema,
  DocumentSchema,
  DocumentTextSchema,
  FileVariantQuerySchema,
  ListDocumentsQuerySchema,
  ReplacePagesRequestSchema,
  UpdateDocumentRequestSchema,
} from '@scanova/contracts/documents';
import { ERROR_DEFINITIONS, type ErrorCode } from '@scanova/contracts/errors';
import {
  ChangePasswordRequestSchema,
  SessionListSchema,
  UpdateMeRequestSchema,
} from '@scanova/contracts/me';
import { HealthSchema, ReadinessSchema, ServerInfoSchema } from '@scanova/contracts/system';
import { z } from 'zod';
import { SERVER_VERSION } from './version.js';

type Auth = 'none' | 'user' | 'service' | 'admin';

interface Operation {
  readonly method: 'get' | 'post' | 'patch' | 'put' | 'delete';
  readonly path: string;
  readonly summary: string;
  readonly tag: string;
  readonly auth: Auth;
  readonly body?: z.ZodType;
  readonly query?: z.ZodType;
  /** Schema of `data` in the envelope. Omitted for the file download. */
  readonly data?: z.ZodType;
  readonly status?: number;
  readonly paginated?: boolean;
  readonly errors: readonly ErrorCode[];
  /** Media type of a raw, streamed request body (uploads). */
  readonly rawBody?: 'application/pdf' | 'image/jpeg';
  /** Media type of a raw, not enveloped success body (downloads). */
  readonly rawResponse?: 'application/pdf' | 'image/jpeg';
}

const CLIENT = '/api/v1';
const ADMIN = '/internal/admin/v1';
const ALWAYS: readonly ErrorCode[] = ['INTERNAL_ERROR', 'SERVICE_UNAVAILABLE'];
const AUTHENTICATED: readonly ErrorCode[] = [
  'UNAUTHENTICATED',
  'SESSION_REVOKED',
  'ACCOUNT_DISABLED',
  'PASSWORD_CHANGE_REQUIRED',
];

/** Every route of the API contract (§7.3, §7.4), described with the same Zod schemas the server validates with. */
export const OPERATIONS: readonly Operation[] = [
  {
    method: 'get',
    path: `${CLIENT}/health`,
    tag: 'system',
    auth: 'none',
    summary: 'Liveness',
    data: HealthSchema,
    errors: [],
  },
  {
    method: 'get',
    path: `${CLIENT}/health/ready`,
    tag: 'system',
    auth: 'none',
    summary: 'Readiness of database, storage and vault',
    data: ReadinessSchema,
    errors: [],
  },
  {
    method: 'get',
    path: `${CLIENT}/server-info`,
    tag: 'system',
    auth: 'none',
    summary: 'Server name, API version and features',
    data: ServerInfoSchema,
    errors: [],
  },
  {
    method: 'post',
    path: `${CLIENT}/auth/login`,
    tag: 'auth',
    auth: 'none',
    summary: 'Sign in on the phone',
    body: LoginRequestSchema,
    data: TokensSchema,
    errors: ['VALIDATION_FAILED', 'INVALID_CREDENTIALS', 'ACCOUNT_DISABLED', 'RATE_LIMITED'],
  },
  {
    method: 'post',
    path: `${CLIENT}/auth/register`,
    tag: 'auth',
    auth: 'none',
    summary: 'Create an account from the phone (open sign-up, when enabled)',
    body: RegisterRequestSchema,
    data: TokensSchema,
    status: 201,
    errors: ['VALIDATION_FAILED', 'ID_CONFLICT', 'FORBIDDEN', 'RATE_LIMITED'],
  },
  {
    method: 'post',
    path: `${CLIENT}/auth/refresh`,
    tag: 'auth',
    auth: 'none',
    summary: 'Exchange a refresh token (single use)',
    body: RefreshRequestSchema,
    data: TokensSchema,
    errors: ['VALIDATION_FAILED', 'INVALID_REFRESH_TOKEN', 'SESSION_REVOKED', 'ACCOUNT_DISABLED'],
  },
  {
    method: 'post',
    path: `${CLIENT}/auth/logout`,
    tag: 'auth',
    auth: 'none',
    summary: 'Sign out (idempotent)',
    body: LogoutRequestSchema,
    data: SuccessSchema,
    errors: ['VALIDATION_FAILED'],
  },
  {
    method: 'get',
    path: `${CLIENT}/me`,
    tag: 'me',
    auth: 'user',
    summary: 'Own account (also with a temporary password)',
    data: UserSchema,
    errors: [],
  },
  {
    method: 'patch',
    path: `${CLIENT}/me`,
    tag: 'me',
    auth: 'user',
    summary: 'Change own name',
    body: UpdateMeRequestSchema,
    data: UserSchema,
    errors: ['VALIDATION_FAILED'],
  },
  {
    method: 'post',
    path: `${CLIENT}/me/password`,
    tag: 'me',
    auth: 'user',
    summary: 'Change password; other sessions are revoked',
    body: ChangePasswordRequestSchema,
    data: TokensSchema,
    errors: ['VALIDATION_FAILED', 'INVALID_CREDENTIALS'],
  },
  {
    method: 'get',
    path: `${CLIENT}/ai/runtime`,
    tag: 'ai',
    auth: 'user',
    summary: 'Server AI status (owners only)',
    data: AiRuntimeStatusSchema,
    errors: ['FORBIDDEN'],
  },
  {
    method: 'put',
    path: `${CLIENT}/ai/runtime`,
    tag: 'ai',
    auth: 'user',
    summary: 'Load or unload the server AI models (owners only); answers at once',
    body: SetAiRuntimeRequestSchema,
    data: AiRuntimeStatusSchema,
    errors: ['VALIDATION_FAILED', 'FORBIDDEN', 'NOT_ENOUGH_MEMORY'],
  },
  {
    method: 'post',
    path: `${CLIENT}/ai/runtime/heartbeat`,
    tag: 'ai',
    auth: 'user',
    summary: 'An open app checks in: keeps the models loaded for another 15 minutes',
    data: AiRuntimeStatusSchema,
    errors: ['FORBIDDEN'],
  },
  {
    method: 'get',
    path: `${CLIENT}/me/sessions`,
    tag: 'me',
    auth: 'user',
    summary: 'Own sessions',
    data: SessionListSchema,
    errors: [],
  },
  {
    method: 'delete',
    path: `${CLIENT}/me/sessions/{id}`,
    tag: 'me',
    auth: 'user',
    summary: 'Revoke one of the own sessions',
    data: SuccessSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'get',
    path: `${CLIENT}/documents`,
    tag: 'documents',
    auth: 'user',
    summary: 'List, search and sync documents',
    query: ListDocumentsQuerySchema,
    data: DocumentListSchema,
    paginated: true,
    errors: ['VALIDATION_FAILED'],
  },
  {
    method: 'post',
    path: `${CLIENT}/documents`,
    tag: 'documents',
    auth: 'user',
    summary: 'Create a document (201; 200 if this user already created the id)',
    body: CreateDocumentRequestSchema,
    data: DocumentSchema,
    status: 201,
    errors: ['VALIDATION_FAILED', 'ID_CONFLICT'],
  },
  {
    method: 'get',
    path: `${CLIENT}/documents/{id}`,
    tag: 'documents',
    auth: 'user',
    summary: 'One document',
    data: DocumentSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'patch',
    path: `${CLIENT}/documents/{id}`,
    tag: 'documents',
    auth: 'user',
    summary: 'Change title or tags',
    body: UpdateDocumentRequestSchema,
    data: DocumentSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'delete',
    path: `${CLIENT}/documents/{id}`,
    tag: 'documents',
    auth: 'user',
    summary: 'Soft delete',
    data: SuccessSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'put',
    path: `${CLIENT}/documents/{id}/file`,
    tag: 'documents',
    auth: 'user',
    summary: 'Upload the PDF (streamed; headers Content-Length and X-Content-SHA256)',
    rawBody: 'application/pdf',
    data: DocumentSchema,
    errors: [
      'VALIDATION_FAILED',
      'NOT_FOUND',
      'FILE_TOO_LARGE',
      'QUOTA_EXCEEDED',
      'UNSUPPORTED_MEDIA_TYPE',
      'CHECKSUM_MISMATCH',
      'RATE_LIMITED',
    ],
  },
  {
    method: 'get',
    path: `${CLIENT}/documents/{id}/file`,
    tag: 'documents',
    auth: 'user',
    summary: 'Download the PDF (not enveloped)',
    query: FileVariantQuerySchema,
    rawResponse: 'application/pdf',
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'get',
    path: `${CLIENT}/documents/{id}/text`,
    tag: 'documents',
    auth: 'user',
    summary: 'OCR text',
    data: DocumentTextSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'get',
    path: `${CLIENT}/documents/{id}/pages`,
    tag: 'documents',
    auth: 'user',
    summary: 'Page list: order, size, edit recipe and original of each page',
    data: DocumentPagesSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'put',
    path: `${CLIENT}/documents/{id}/pages`,
    tag: 'documents',
    auth: 'user',
    summary: 'Replace the page list (pages left out are deleted with their originals; idempotent)',
    body: ReplacePagesRequestSchema,
    data: DocumentPagesSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND', 'FILE_TOO_LARGE'],
  },
  {
    method: 'put',
    path: `${CLIENT}/documents/{id}/pages/{pageId}/original`,
    tag: 'documents',
    auth: 'user',
    summary:
      "Upload a page's original photo (streamed JPEG; headers Content-Length and X-Content-SHA256)",
    rawBody: 'image/jpeg',
    data: DocumentPageSchema,
    errors: [
      'VALIDATION_FAILED',
      'NOT_FOUND',
      'FILE_TOO_LARGE',
      'QUOTA_EXCEEDED',
      'UNSUPPORTED_MEDIA_TYPE',
      'CHECKSUM_MISMATCH',
      'RATE_LIMITED',
    ],
  },
  {
    method: 'get',
    path: `${CLIENT}/documents/{id}/pages/{pageId}/original`,
    tag: 'documents',
    auth: 'user',
    summary: "Download a page's original photo (not enveloped)",
    rawResponse: 'image/jpeg',
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'post',
    path: '/internal/web/v1/auth/login',
    tag: 'internal-web',
    auth: 'service',
    summary: 'Web sign-in for members and admins (cli = web)',
    body: WebLoginRequestSchema,
    data: TokensSchema,
    errors: [
      'VALIDATION_FAILED',
      'UNAUTHENTICATED',
      'INVALID_CREDENTIALS',
      'ACCOUNT_DISABLED',
      'RATE_LIMITED',
    ],
  },
  {
    method: 'get',
    path: `${ADMIN}/users`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'List users',
    query: PageQuerySchema,
    data: UserListSchema,
    paginated: true,
    errors: ['VALIDATION_FAILED'],
  },
  {
    method: 'post',
    path: `${ADMIN}/users`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'Create a user with a temporary password (shown once)',
    body: AdminCreateUserRequestSchema,
    data: AdminCreateUserResponseSchema,
    status: 201,
    errors: ['VALIDATION_FAILED', 'ID_CONFLICT'],
  },
  {
    method: 'patch',
    path: `${ADMIN}/users/{id}`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'Change name, status or user type',
    body: AdminUpdateUserRequestSchema,
    data: UserSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'post',
    path: `${ADMIN}/users/{id}/reset-password`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'Temporary password; revokes sessions and rotates the user key',
    data: AdminResetPasswordResponseSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'post',
    path: `${ADMIN}/users/{id}/revoke-sessions`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'Revoke every session of a user',
    data: SuccessSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'delete',
    path: `${ADMIN}/users/{id}`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'Crypto-shred and purge a user',
    data: SuccessSchema,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'get',
    path: `${ADMIN}/keys`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'Key versions, ages and rows pending re-wrap',
    data: KeyStatusSchema,
    errors: [],
  },
  {
    method: 'post',
    path: `${ADMIN}/keys/rotations`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'Request a key rotation',
    body: CreateRotationRequestSchema,
    data: RotationRunSchema,
    status: 201,
    errors: ['VALIDATION_FAILED', 'NOT_FOUND'],
  },
  {
    method: 'get',
    path: `${ADMIN}/keys/rotations`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'Rotation history and progress',
    query: PageQuerySchema,
    data: RotationRunListSchema,
    paginated: true,
    errors: ['VALIDATION_FAILED'],
  },
  {
    method: 'get',
    path: `${ADMIN}/audit-events`,
    tag: 'internal-admin',
    auth: 'admin',
    summary: 'Audit log (newest first; filter by action and/or actor)',
    query: AuditEventQuerySchema,
    data: AuditEventListSchema,
    paginated: true,
    errors: ['VALIDATION_FAILED'],
  },
];

type Json = Record<string, unknown>;

function jsonSchema(schema: z.ZodType): Json {
  // The request side of each schema: what goes over the wire, before server-side normalisation.
  const { $schema: _dialect, ...rest } = z.toJSONSchema(schema, {
    io: 'input',
    unrepresentable: 'any',
  }) as Json;
  return rest;
}

function envelope(data: Json, paginated: boolean): Json {
  return {
    type: 'object',
    required: ['data', 'error', 'meta'],
    properties: {
      data,
      error: { type: 'null' },
      meta: {
        type: 'object',
        required: ['nextCursor'],
        properties: {
          nextCursor: paginated ? { type: ['string', 'null'] } : { type: 'null' },
        },
      },
    },
  };
}

function errorResponses(codes: readonly ErrorCode[]): Json {
  const byStatus = new Map<number, ErrorCode[]>();
  for (const code of codes) {
    const status = ERROR_DEFINITIONS[code].status;
    byStatus.set(status, [...(byStatus.get(status) ?? []), code]);
  }
  const responses: Json = {};
  for (const [status, statusCodes] of [...byStatus].sort(([a], [b]) => a - b)) {
    responses[String(status)] = {
      description: statusCodes.join(' | '),
      content: {
        'application/json': {
          schema: {
            allOf: [
              { $ref: '#/components/schemas/ErrorEnvelope' },
              { properties: { error: { properties: { code: { enum: statusCodes } } } } },
            ],
          },
        },
      },
    };
  }
  return responses;
}

function parametersOf(operation: Operation): Json[] {
  const parameters: Json[] = [];
  for (const match of operation.path.matchAll(/\{(\w+)\}/gu)) {
    parameters.push({
      name: match[1],
      in: 'path',
      required: true,
      schema: { type: 'string', format: 'uuid' },
    });
  }
  if (operation.query !== undefined) {
    const schema = jsonSchema(operation.query) as {
      properties?: Record<string, Json>;
      required?: string[];
    };
    for (const [name, property] of Object.entries(schema.properties ?? {})) {
      parameters.push({
        name,
        in: 'query',
        required: schema.required?.includes(name) ?? false,
        schema: property,
      });
    }
  }
  if (operation.rawBody !== undefined) {
    parameters.push({
      name: 'X-Content-SHA256',
      in: 'header',
      required: true,
      description: 'SHA-256 of the body, 64 hex characters.',
      schema: { type: 'string', pattern: '^[0-9a-fA-F]{64}$' },
    });
  }
  return parameters;
}

/** Builds the OpenAPI 3.1 document for the API. Pure: the same input always gives the same document. */
export function buildOpenApiDocument(): Json {
  const paths: Record<string, Json> = {};
  for (const operation of OPERATIONS) {
    const errors = [
      ...operation.errors,
      ...(operation.auth === 'user' || operation.auth === 'admin' ? AUTHENTICATED : []),
      ...(operation.auth === 'admin' ? (['FORBIDDEN'] as const) : []),
      ...(operation.path === `${CLIENT}/health` ? [] : ALWAYS),
    ];
    const success: Json =
      operation.rawResponse !== undefined
        ? {
            description: operation.rawResponse === 'application/pdf' ? 'PDF bytes' : 'JPEG bytes',
            content: {
              [operation.rawResponse]: { schema: { type: 'string', format: 'binary' } },
            },
          }
        : {
            description: 'Success',
            content: {
              'application/json': {
                schema: envelope(
                  operation.data === undefined ? {} : jsonSchema(operation.data),
                  operation.paginated === true,
                ),
              },
            },
          };
    const requestBody: Json | undefined =
      operation.rawBody !== undefined
        ? {
            required: true,
            content: { [operation.rawBody]: { schema: { type: 'string', format: 'binary' } } },
          }
        : operation.body === undefined
          ? undefined
          : {
              required: true,
              content: { 'application/json': { schema: jsonSchema(operation.body) } },
            };
    const security: Json[] =
      operation.auth === 'none'
        ? []
        : operation.auth === 'user'
          ? [{ accessToken: [] }]
          : operation.auth === 'service'
            ? [{ serviceToken: [] }]
            : [{ accessToken: [], serviceToken: [] }];
    const parameters = parametersOf(operation);
    paths[operation.path] = {
      ...paths[operation.path],
      [operation.method]: {
        operationId: `${operation.method}${operation.path.replace(/[^A-Za-z0-9]+/gu, '_')}`.replace(
          /_$/u,
          '',
        ),
        summary: operation.summary,
        tags: [operation.tag],
        security,
        ...(parameters.length === 0 ? {} : { parameters }),
        ...(requestBody === undefined ? {} : { requestBody }),
        responses: {
          [String(operation.status ?? 200)]: success,
          ...errorResponses([...new Set(errors)]),
        },
      },
    };
  }
  return {
    openapi: '3.1.0',
    info: {
      title: 'Scanova API',
      version: SERVER_VERSION,
      description:
        'Generated from the Zod schemas in @scanova/contracts. Every JSON answer is the envelope ' +
        '{ data, error, meta }. Timestamps are UTC in the form YYYY-MM-DDTHH:mm:ss.sssZ. ' +
        '/internal/* is reachable from the web app only and is never proxied by the edge.',
    },
    servers: [{ url: '/', description: 'The API origin (behind the edge proxy for /api/v1)' }],
    tags: [
      { name: 'system' },
      { name: 'auth' },
      { name: 'me' },
      { name: 'documents' },
      { name: 'ai', description: 'The server AI switch. Owners only (operator CLI owner:grant).' },
      { name: 'internal-web', description: 'Requires X-Scanova-Service-Token.' },
      {
        name: 'internal-admin',
        description: 'Requires the service token and an access token with typ=ADMIN, cli=web.',
      },
    ],
    paths,
    components: {
      securitySchemes: {
        accessToken: { type: 'http', scheme: 'bearer', bearerFormat: 'JWT (EdDSA)' },
        serviceToken: { type: 'apiKey', in: 'header', name: 'X-Scanova-Service-Token' },
      },
      schemas: {
        ErrorEnvelope: {
          type: 'object',
          required: ['data', 'error'],
          properties: {
            data: { type: 'null' },
            error: {
              type: 'object',
              required: ['code', 'message'],
              properties: {
                code: { type: 'string', enum: Object.keys(ERROR_DEFINITIONS) },
                message: { type: 'string' },
                details: {},
              },
            },
            meta: { type: 'object', properties: { nextCursor: { type: 'null' } } },
          },
        },
      },
    },
  };
}

/** `node dist/openapi.js <output file>` writes the document; without an argument it prints it. */
function main(): void {
  const document = `${JSON.stringify(buildOpenApiDocument(), null, 2)}\n`;
  const target = process.argv[2];
  if (target === undefined) {
    process.stdout.write(document);
    return;
  }
  const path = resolve(target);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, document);
  process.stdout.write(`Wrote ${path}\n`);
}

if (process.argv[1] !== undefined && import.meta.filename === resolve(process.argv[1])) {
  main();
}
