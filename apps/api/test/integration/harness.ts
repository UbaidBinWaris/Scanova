import { createHash, randomBytes, randomUUID } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import type { NestFastifyApplication } from '@nestjs/platform-fastify';
import { buildPostgresConfig } from '@scanova/config/postgres';
import type { ApiConfig, PostgresConfig } from '@scanova/config/sections';
import type { Tokens } from '@scanova/contracts/auth';
import type { Document, DocumentPage } from '@scanova/contracts/documents';
import { createDatabaseClient, type DatabaseClient } from '@scanova/database/client';
import { Keyring } from '@scanova/keyring/keyring';
import { createKeystoreClient, type KeystoreClient } from '@scanova/keystore/client';
import { OpenBaoKms } from '@scanova/kms/openbao';
import { S3ObjectStore } from '@scanova/storage/s3';
import { readIntegrationState } from '@scanova/tooling/integration/state';
import { createApp } from '../../src/bootstrap.js';
import { CLI_ACTOR } from '../../src/internal/admin/actor.js';
import { AdminUsersService } from '../../src/internal/admin/admin-users.service.js';
import { AppLogger } from '@scanova/config/logger';

export const state = readIntegrationState();

export const SERVICE_TOKEN = 'integration-test-web-service-token-0123456789abcdef';
export const SERVICE_HEADER = { 'x-scanova-service-token': SERVICE_TOKEN };

function postgres(target: 'main' | 'keys', role: 'app' | 'migrator' = 'app'): PostgresConfig {
  const database = state.postgres[target];
  return buildPostgresConfig({
    host: state.postgres.host,
    port: state.postgres.port,
    database: database.database,
    ...database[role],
    tls: { caCertPath: state.pki.caCertPath },
  });
}

/** A complete API configuration for the throwaway stack, built by hand: tests never read `.env`. */
export function testConfig(overrides: Partial<ApiConfig> = {}): ApiConfig {
  return {
    runtime: { nodeEnv: 'test', logLevel: 'fatal' },
    http: { port: 0, maxUploadBytes: 32 * 1024 * 1024 },
    auth: {
      jwtIssuer: 'scanova-test',
      accessTokenTtlSeconds: 600,
      refreshTokenTtlDays: 30,
      // Deliberately weak: these tests are not about password hashing cost.
      argon2: { memoryKib: 8192, timeCost: 1, parallelism: 1 },
      loginMaxAttemptsPerMinute: 10_000,
      webServiceToken: SERVICE_TOKEN,
    },
    limits: { userDefaultQuotaBytes: 256 * 1024 * 1024, trashRetentionDays: 30 },
    features: { serverOcr: true, ai: false, signup: true },
    ai: { provider: 'none' },
    aiControl: { keepFreeBytes: 2048 * 1024 * 1024 },
    signup: {
      enabled: true,
      quotaBytes: 64 * 1024 * 1024,
      maxPerIpPerHour: 10_000,
      maxPerHour: 10_000,
    },
    database: postgres('main'),
    keystore: postgres('keys'),
    storage: {
      endpoint: state.s3.endpoint,
      region: state.s3.region,
      bucket: state.s3.bucket,
      forcePathStyle: true,
      accessKeyId: state.s3.accessKeyId,
      secretAccessKey: state.s3.secretAccessKey,
    },
    kms: {
      address: state.openbao.address,
      transitMount: state.openbao.transitMount,
      caCertPath: null,
      roleId: state.openbao.api.roleId,
      secretId: state.openbao.api.secretId,
    },
    keys: state.openbao.keys,
    rotation: { userTypeKeyDays: 90, userKeyDays: 180, jwtKeyDays: 30, rewrapBatchSize: 3 },
    ...overrides,
  };
}

export interface ApiResponse<Data = unknown> {
  readonly status: number;
  readonly headers: Headers;
  /** The parsed envelope, or null when the body is not JSON. */
  readonly body: {
    data: Data;
    error: { code: string; message: string; details?: unknown } | null;
    meta?: { nextCursor: string | null };
  } | null;
  readonly raw: Buffer;
}

/** A tiny HTTP client for one base URL and (optionally) one signed-in user. */
export class ApiClient {
  public constructor(
    public readonly baseUrl: string,
    private readonly accessToken: string | null = null,
    private readonly extraHeaders: Record<string, string> = {},
  ) {}

  public as(tokens: { accessToken: string }): ApiClient {
    return new ApiClient(this.baseUrl, tokens.accessToken, this.extraHeaders);
  }

  public withHeaders(headers: Record<string, string>): ApiClient {
    return new ApiClient(this.baseUrl, this.accessToken, { ...this.extraHeaders, ...headers });
  }

  public async request<Data = unknown>(
    method: string,
    path: string,
    options: {
      json?: unknown;
      body?: Uint8Array | ReadableStream<Uint8Array>;
      headers?: Record<string, string>;
    } = {},
  ): Promise<ApiResponse<Data>> {
    const headers: Record<string, string> = { ...this.extraHeaders, ...options.headers };
    if (this.accessToken !== null) {
      headers.authorization = `Bearer ${this.accessToken}`;
    }
    let body: RequestInit['body'];
    if (options.json !== undefined) {
      headers['content-type'] = 'application/json';
      body = JSON.stringify(options.json);
    } else if (options.body !== undefined) {
      body = options.body as RequestInit['body'];
    }
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body,
      ...(options.body instanceof ReadableStream ? { duplex: 'half' } : {}),
    });
    const raw = Buffer.from(await response.arrayBuffer());
    let parsed: ApiResponse<Data>['body'] = null;
    if ((response.headers.get('content-type') ?? '').includes('application/json')) {
      parsed = JSON.parse(raw.toString('utf8')) as ApiResponse<Data>['body'];
    }
    return { status: response.status, headers: response.headers, body: parsed, raw };
  }

  public get<Data = unknown>(path: string): Promise<ApiResponse<Data>> {
    return this.request<Data>('GET', path);
  }

  public post<Data = unknown>(path: string, json?: unknown): Promise<ApiResponse<Data>> {
    return this.request<Data>('POST', path, json === undefined ? {} : { json });
  }

  public patch<Data = unknown>(path: string, json: unknown): Promise<ApiResponse<Data>> {
    return this.request<Data>('PATCH', path, { json });
  }

  public put<Data = unknown>(path: string, json: unknown): Promise<ApiResponse<Data>> {
    return this.request<Data>('PUT', path, { json });
  }

  public delete<Data = unknown>(path: string): Promise<ApiResponse<Data>> {
    return this.request<Data>('DELETE', path);
  }

  /** `PUT /documents/:id/pages/:pageId/original` with the headers the contract asks for. */
  public uploadOriginal(
    documentId: string,
    pageId: string,
    file: Buffer,
    overrides: { sha256?: string; contentType?: string } = {},
  ): Promise<ApiResponse<DocumentPage>> {
    return this.request<DocumentPage>(
      'PUT',
      `/api/v1/documents/${documentId}/pages/${pageId}/original`,
      {
        body: file,
        headers: {
          'content-type': overrides.contentType ?? 'image/jpeg',
          'x-content-sha256': overrides.sha256 ?? sha256(file),
        },
      },
    );
  }

  /** `PUT /documents/:id/file` with the headers the contract asks for. */
  public uploadPdf(
    documentId: string,
    file: Buffer,
    overrides: { sha256?: string; contentType?: string } = {},
  ): Promise<ApiResponse<Document>> {
    return this.request<Document>('PUT', `/api/v1/documents/${documentId}/file`, {
      body: file,
      headers: {
        'content-type': overrides.contentType ?? 'application/pdf',
        'x-content-sha256': overrides.sha256 ?? sha256(file),
      },
    });
  }
}

export interface TestApp {
  readonly app: NestFastifyApplication;
  readonly config: ApiConfig;
  readonly client: ApiClient;
  /** Every log line the application wrote, for assertions about what is (not) logged. */
  readonly logs: string[];
  /** Moves the application's clock forward. */
  advance(ms: number): void;
  close(): Promise<void>;
}

const openApps: TestApp[] = [];

/** Starts the real application on an ephemeral port against the throwaway stack. */
export async function startApp(overrides: Partial<ApiConfig> = {}): Promise<TestApp> {
  const config = testConfig(overrides);
  const logs: string[] = [];
  let offsetMs = 0;
  const app = await createApp(config, {
    logger: new AppLogger('debug', 'api', (line) => logs.push(line)),
    clock: () => new Date(Date.now() + offsetMs),
  });
  await app.listen(0, '127.0.0.1');
  const address = app.getHttpServer().address() as AddressInfo;
  const testApp: TestApp = {
    app,
    config,
    client: new ApiClient(`http://127.0.0.1:${address.port}`),
    logs,
    advance: (ms) => {
      offsetMs += ms;
    },
    close: async () => {
      await app.close();
    },
  };
  openApps.push(testApp);
  return testApp;
}

export async function closeAllApps(): Promise<void> {
  await Promise.all(openApps.splice(0).map((app) => app.close()));
}

// ── direct views of the data stores, for assertions only ─────────────────────

let mainInspector: DatabaseClient | null = null;
let keysInspector: KeystoreClient | null = null;
let rawStore: S3ObjectStore | null = null;
const workerKeyrings: Keyring[] = [];
const vaultClients: OpenBaoKms[] = [];

export function mainDb(): DatabaseClient {
  mainInspector ??= createDatabaseClient(postgres('main'));
  return mainInspector;
}

export function keysDb(): KeystoreClient {
  keysInspector ??= createKeystoreClient(postgres('keys'));
  return keysInspector;
}

export function bucket(): S3ObjectStore {
  rawStore ??= new S3ObjectStore(testConfig().storage);
  return rawStore;
}

export function vault(role: 'api' | 'worker'): OpenBaoKms {
  const client = new OpenBaoKms({
    address: state.openbao.address,
    transitMount: state.openbao.transitMount,
    caCertPath: null,
    roleId: state.openbao[role].roleId,
    secretId: state.openbao[role].secretId,
  });
  vaultClients.push(client);
  return client;
}

/**
 * A keyring with the worker's vault role and an empty cache: what the worker
 * process uses to carry out a rotation the API requested.
 */
export function workerKeyring(
  settings: { masterKeySettleSeconds?: number; now?: () => Date } = {},
): Keyring {
  const config = testConfig();
  const keyring = new Keyring({
    kms: vault('worker'),
    keystore: createKeystoreClient(postgres('keys')),
    settings: {
      keys: config.keys,
      rotation: config.rotation,
      accessTokenTtlSeconds: config.auth.accessTokenTtlSeconds,
      masterKeySettleSeconds: settings.masterKeySettleSeconds ?? 0,
      ...(settings.now === undefined ? {} : { now: settings.now }),
    },
  });
  workerKeyrings.push(keyring);
  return keyring;
}

export async function closeInspectors(): Promise<void> {
  await Promise.all(workerKeyrings.splice(0).map((keyring) => keyring.close()));
  await Promise.all(vaultClients.splice(0).map((client) => client.close()));
  await mainInspector?.$disconnect();
  await keysInspector?.$disconnect();
  rawStore?.close();
  mainInspector = null;
  keysInspector = null;
  rawStore = null;
}

// ── fixtures ──────────────────────────────────────────────────────────────────

export function sha256(data: Uint8Array): string {
  return createHash('sha256').update(data).digest('hex');
}

/** Bytes that start like a PDF. The server only checks the signature. */
export function pdf(size: number = 4096): Buffer {
  const head = Buffer.from('%PDF-1.7\n');
  return Buffer.concat([head, randomBytes(Math.max(0, size - head.length))]);
}

/** Bytes that start like a JPEG. The server only checks the signature. */
export function jpeg(size: number = 4096): Buffer {
  const head = Buffer.from([0xff, 0xd8, 0xff, 0xe0]);
  return Buffer.concat([head, randomBytes(Math.max(0, size - head.length))]);
}

export function uniqueEmail(prefix: string = 'user'): string {
  return `${prefix}-${randomBytes(6).toString('hex')}@example.test`;
}

export const DEVICE = {
  name: 'Pixel 9',
  platform: 'android',
  model: 'Pixel 9 Pro',
  appVersion: '1.0.0',
} as const;

export interface TestUser {
  readonly id: string;
  readonly email: string;
  readonly password: string;
  readonly tokens: Tokens;
  readonly client: ApiClient;
}

/** Creates an account the way the operator CLI does: with a temporary password. */
export async function createAccount(
  testApp: TestApp,
  userType: 'ADMIN' | 'MEMBER',
  email: string = uniqueEmail(userType.toLowerCase()),
): Promise<{ id: string; email: string; temporaryPassword: string }> {
  const created = await testApp.app
    .get(AdminUsersService)
    .create(CLI_ACTOR, { email, name: `Test ${userType}`, userType });
  return { id: created.user.id, email, temporaryPassword: created.temporaryPassword };
}

/** A member who has signed in on the phone and already replaced the temporary password. */
export async function createMember(testApp: TestApp, email?: string): Promise<TestUser> {
  const account = await createAccount(testApp, 'MEMBER', email);
  const login = await testApp.client.post<Tokens>('/api/v1/auth/login', {
    email: account.email,
    password: account.temporaryPassword,
    client: 'mobile',
    device: DEVICE,
  });
  if (login.status !== 200 || login.body === null) {
    throw new Error(`Member sign-in failed with ${login.status}`);
  }
  const password = `member-password-${randomBytes(6).toString('hex')}`;
  const changed = await testApp.client.as(login.body.data).post<Tokens>('/api/v1/me/password', {
    currentPassword: account.temporaryPassword,
    newPassword: password,
  });
  if (changed.status !== 200 || changed.body === null) {
    throw new Error(`Password change failed with ${changed.status}`);
  }
  return {
    id: account.id,
    email: account.email,
    password,
    tokens: changed.body.data,
    client: testApp.client.as(changed.body.data),
  };
}

/** An admin signed in through the internal web login, past the forced password change. */
export async function createAdmin(testApp: TestApp): Promise<TestUser> {
  const account = await createAccount(testApp, 'ADMIN');
  const web = testApp.client.withHeaders(SERVICE_HEADER);
  const login = await web.post<Tokens>('/internal/web/v1/auth/login', {
    email: account.email,
    password: account.temporaryPassword,
  });
  if (login.status !== 200 || login.body === null) {
    throw new Error(`Admin sign-in failed with ${login.status}`);
  }
  const password = `admin-password-${randomBytes(6).toString('hex')}`;
  const changed = await web.as(login.body.data).post<Tokens>('/api/v1/me/password', {
    currentPassword: account.temporaryPassword,
    newPassword: password,
  });
  if (changed.status !== 200 || changed.body === null) {
    throw new Error(`Admin password change failed with ${changed.status}`);
  }
  return {
    id: account.id,
    email: account.email,
    password,
    tokens: changed.body.data,
    client: web.as(changed.body.data),
  };
}

/** Creates a document and uploads a PDF for it. */
export async function createDocument(
  user: TestUser,
  options: { title?: string; tags?: string[]; clientOcrText?: string; file?: Buffer | null } = {},
): Promise<{ id: string; file: Buffer | null; document: Document }> {
  const id = randomUUID();
  const created = await user.client.post<Document>('/api/v1/documents', {
    id,
    title: options.title ?? 'Test document',
    pageCount: 1,
    capturedAt: new Date().toISOString(),
    ...(options.tags === undefined ? {} : { tags: options.tags }),
    ...(options.clientOcrText === undefined ? {} : { clientOcrText: options.clientOcrText }),
  });
  if (created.status !== 201 || created.body === null) {
    throw new Error(`Document creation failed with ${created.status}`);
  }
  if (options.file === null) {
    return { id, file: null, document: created.body.data };
  }
  const file = options.file ?? pdf();
  const uploaded = await user.client.uploadPdf(id, file);
  if (uploaded.status !== 200 || uploaded.body === null) {
    throw new Error(
      `Upload failed with ${uploaded.status}: ${uploaded.raw.toString('utf8').slice(0, 200)}`,
    );
  }
  return { id, file, document: uploaded.body.data };
}
