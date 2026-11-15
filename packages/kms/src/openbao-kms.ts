import { readFileSync } from 'node:fs';
import type { KmsConfig } from '@scanova/config/sections';
import { z } from 'zod';
import { KmsError } from './errors.js';
import { VaultHttpClient, type VaultResponse } from './http-client.js';
import type { KeyInfo, KeyVersionInfo, Kms, RewrapItem, SignResult } from './kms.js';

/** Schemas for the parts of the vault API this client reads (§10: Zod at every boundary). */
const AuthResponseSchema = z.object({
  auth: z.object({
    client_token: z.string().min(1),
    lease_duration: z.number().int().nonnegative(),
    renewable: z.boolean(),
  }),
});

const EncryptResponseSchema = z.object({ data: z.object({ ciphertext: z.string().min(1) }) });
const DecryptResponseSchema = z.object({ data: z.object({ plaintext: z.string() }) });
const RewrapResponseSchema = z.object({
  data: z.object({
    batch_results: z.array(
      z.object({ ciphertext: z.string().optional(), error: z.string().optional() }),
    ),
  }),
});
const SignResponseSchema = z.object({
  data: z.object({ signature: z.string().min(1), key_version: z.number().int().optional() }),
});

// Symmetric keys list a creation timestamp per version; signing keys an object with the public key.
const KeyVersionSchema = z.union([
  z.number(),
  z.object({ creation_time: z.string(), public_key: z.string().optional() }),
]);
const KeyResponseSchema = z.object({
  data: z.object({
    type: z.string(),
    derived: z.boolean().optional(),
    exportable: z.boolean().optional(),
    deletion_allowed: z.boolean().optional(),
    latest_version: z.number().int(),
    min_decryption_version: z.number().int(),
    keys: z.record(z.string(), KeyVersionSchema),
  }),
});

const HealthResponseSchema = z.object({ initialized: z.boolean(), sealed: z.boolean() });

const SIGNATURE_PATTERN = /^vault:v(\d+):([A-Za-z0-9+/]+=*)$/u;
const REQUEST_TIMEOUT_MS = 10_000;
/** Renew when two thirds of the lease are gone; never more often than this. */
const MIN_RENEW_DELAY_MS = 5_000;

export interface OpenBaoKmsOptions {
  readonly requestTimeoutMs?: number;
}

interface TokenState {
  readonly token: string;
  readonly renewable: boolean;
  readonly leaseSeconds: number;
}

/**
 * `Kms` over the OpenBao Transit engine.
 *
 * Authentication is AppRole: the role id and secret id are exchanged for a
 * short-lived token on first use. The token is renewed in the background at two
 * thirds of its lease; when renewal is no longer possible (max TTL reached,
 * token revoked, vault restarted) the next call logs in again. A 403 on an
 * operation triggers exactly one fresh login and retry, so an expired token
 * heals itself while a genuine policy denial still surfaces.
 */
export class OpenBaoKms implements Kms {
  private readonly http: VaultHttpClient;
  private readonly mount: string;
  private readonly roleId: string;
  private readonly secretId: string;
  private tokenState: TokenState | null = null;
  private login: Promise<TokenState> | null = null;
  private renewTimer: NodeJS.Timeout | null = null;
  private closed = false;

  public constructor(config: KmsConfig, options: OpenBaoKmsOptions = {}) {
    this.http = new VaultHttpClient({
      address: config.address,
      caCertificate: config.caCertPath === null ? null : readFileSync(config.caCertPath, 'utf8'),
      timeoutMs: options.requestTimeoutMs ?? REQUEST_TIMEOUT_MS,
    });
    this.mount = encodeURIComponent(config.transitMount);
    this.roleId = config.roleId;
    this.secretId = config.secretId;
  }

  public async encrypt(keyName: string, plaintext: Uint8Array, context?: string): Promise<string> {
    const response = await this.transit('POST', `encrypt/${encodeURIComponent(keyName)}`, {
      plaintext: Buffer.from(plaintext).toString('base64'),
      ...contextField(context),
    });
    return parse(EncryptResponseSchema, response).data.ciphertext;
  }

  public async decrypt(keyName: string, ciphertext: string, context?: string): Promise<Buffer> {
    const response = await this.transit('POST', `decrypt/${encodeURIComponent(keyName)}`, {
      ciphertext,
      ...contextField(context),
    });
    return Buffer.from(parse(DecryptResponseSchema, response).data.plaintext, 'base64');
  }

  public async rewrap(keyName: string, items: readonly RewrapItem[]): Promise<string[]> {
    if (items.length === 0) {
      return [];
    }
    const response = await this.transit('POST', `rewrap/${encodeURIComponent(keyName)}`, {
      batch_input: items.map((item) => ({
        ciphertext: item.ciphertext,
        ...contextField(item.context),
      })),
    });
    const results = parse(RewrapResponseSchema, response).data.batch_results;
    if (results.length !== items.length) {
      throw new KmsError('UNEXPECTED_RESPONSE', 'Key vault returned a partial re-wrap batch.');
    }
    return results.map((result) => {
      if (result.ciphertext === undefined || result.ciphertext.length === 0) {
        throw new KmsError('REJECTED', 'Key vault refused to re-wrap an item.');
      }
      return result.ciphertext;
    });
  }

  public async sign(keyName: string, data: Uint8Array, keyVersion?: number): Promise<SignResult> {
    const response = await this.transit('POST', `sign/${encodeURIComponent(keyName)}`, {
      input: Buffer.from(data).toString('base64'),
      ...(keyVersion === undefined ? {} : { key_version: keyVersion }),
    });
    const parsed = parse(SignResponseSchema, response).data;
    const match = SIGNATURE_PATTERN.exec(parsed.signature);
    if (match === null) {
      throw new KmsError('UNEXPECTED_RESPONSE', 'Key vault returned a malformed signature.');
    }
    return { signature: Buffer.from(match[2]!, 'base64'), keyVersion: Number(match[1]) };
  }

  public async getKeyInfo(keyName: string): Promise<KeyInfo> {
    const response = await this.transit('GET', `keys/${encodeURIComponent(keyName)}`);
    return toKeyInfo(keyName, parse(KeyResponseSchema, response).data);
  }

  public async rotateKey(keyName: string): Promise<KeyInfo> {
    await this.transit('POST', `keys/${encodeURIComponent(keyName)}/rotate`, {});
    return this.getKeyInfo(keyName);
  }

  public async setMinDecryptionVersion(keyName: string, version: number): Promise<void> {
    await this.transit('POST', `keys/${encodeURIComponent(keyName)}/config`, {
      min_decryption_version: version,
    });
  }

  public async health(): Promise<void> {
    const response = await this.http.request('GET', '/v1/sys/health');
    const parsed = HealthResponseSchema.safeParse(response.body);
    if (
      response.status !== 200 ||
      !parsed.success ||
      !parsed.data.initialized ||
      parsed.data.sealed
    ) {
      throw new KmsError('UNAVAILABLE', 'Key vault is not ready.');
    }
    // Being reachable is not enough: the credentials must still be accepted.
    await this.token();
  }

  public close(): Promise<void> {
    this.closed = true;
    if (this.renewTimer !== null) {
      clearTimeout(this.renewTimer);
      this.renewTimer = null;
    }
    this.tokenState = null;
    this.http.close();
    return Promise.resolve();
  }

  private async transit(method: 'GET' | 'POST', path: string, body?: unknown): Promise<unknown> {
    const fullPath = `/v1/${this.mount}/${path}`;
    let response = await this.http.request(method, fullPath, { token: await this.token(), body });
    if (response.status === 403) {
      // The token may have expired or been revoked. Log in once more, then trust the answer.
      this.dropToken();
      response = await this.http.request(method, fullPath, { token: await this.token(), body });
    }
    return unwrap(response);
  }

  private async token(): Promise<string> {
    if (this.closed) {
      throw new KmsError('UNAVAILABLE', 'Key vault client is closed.');
    }
    if (this.tokenState !== null) {
      return this.tokenState.token;
    }
    // Concurrent callers share one login.
    this.login ??= this.performLogin().finally(() => {
      this.login = null;
    });
    return (await this.login).token;
  }

  private async performLogin(): Promise<TokenState> {
    const response = await this.http.request('POST', '/v1/auth/approle/login', {
      body: { role_id: this.roleId, secret_id: this.secretId },
    });
    if (response.status === 400 || response.status === 403) {
      throw new KmsError('PERMISSION_DENIED', 'Key vault rejected the AppRole credentials.');
    }
    const state = toTokenState(parse(AuthResponseSchema, unwrap(response)));
    this.tokenState = state;
    this.scheduleRenewal(state);
    return state;
  }

  private scheduleRenewal(state: TokenState): void {
    if (this.renewTimer !== null) {
      clearTimeout(this.renewTimer);
      this.renewTimer = null;
    }
    if (this.closed || state.leaseSeconds === 0) {
      return;
    }
    const delayMs = Math.max(MIN_RENEW_DELAY_MS, Math.floor((state.leaseSeconds * 1000 * 2) / 3));
    this.renewTimer = setTimeout(() => {
      void this.renew(state);
    }, delayMs);
    // Never keep the process alive just to renew a token.
    this.renewTimer.unref();
  }

  private async renew(state: TokenState): Promise<void> {
    if (this.closed || this.tokenState !== state) {
      return;
    }
    if (!state.renewable) {
      this.dropToken();
      return;
    }
    try {
      const response = await this.http.request('POST', '/v1/auth/token/renew-self', {
        token: state.token,
        body: {},
      });
      const renewed = toTokenState(parse(AuthResponseSchema, unwrap(response)));
      if (this.tokenState !== state || this.closed) {
        return;
      }
      // A lease that no longer grows has hit its max TTL: log in afresh next time.
      if (renewed.leaseSeconds <= MIN_RENEW_DELAY_MS / 1000) {
        this.dropToken();
        return;
      }
      this.tokenState = renewed;
      this.scheduleRenewal(renewed);
    } catch {
      // Renewal failed (revoked, vault restarted, network). The next call logs in again.
      if (this.tokenState === state) {
        this.dropToken();
      }
    }
  }

  private dropToken(): void {
    this.tokenState = null;
    if (this.renewTimer !== null) {
      clearTimeout(this.renewTimer);
      this.renewTimer = null;
    }
  }
}

function contextField(context: string | undefined): { context?: string } {
  return context === undefined ? {} : { context: Buffer.from(context, 'utf8').toString('base64') };
}

function toTokenState(response: z.infer<typeof AuthResponseSchema>): TokenState {
  return {
    token: response.auth.client_token,
    renewable: response.auth.renewable,
    leaseSeconds: response.auth.lease_duration,
  };
}

/** Maps vault status codes to typed errors. Vault error strings are not forwarded: they can echo input. */
function unwrap(response: VaultResponse): unknown {
  if (response.status >= 200 && response.status < 300) {
    return response.body;
  }
  switch (response.status) {
    case 400:
      return fail('REJECTED', 'Key vault rejected the request.');
    case 403:
      return fail('PERMISSION_DENIED', 'Key vault denied the operation.');
    case 404:
      return fail('NOT_FOUND', 'Key vault has no such key or path.');
    default:
      return fail('UNAVAILABLE', `Key vault answered with status ${response.status}.`);
  }
}

function fail(code: KmsError['code'], message: string): never {
  throw new KmsError(code, message);
}

function parse<Schema extends z.ZodType>(schema: Schema, body: unknown): z.infer<Schema> {
  const result = schema.safeParse(body);
  if (!result.success) {
    throw new KmsError('UNEXPECTED_RESPONSE', 'Key vault answer did not match the expected shape.');
  }
  return result.data;
}

function toKeyInfo(name: string, data: z.infer<typeof KeyResponseSchema>['data']): KeyInfo {
  const versions: KeyVersionInfo[] = Object.entries(data.keys)
    .map(([version, value]) => ({
      version: Number(version),
      createdAt: typeof value === 'number' ? new Date(value * 1000) : new Date(value.creation_time),
      publicKey:
        typeof value === 'number' || value.public_key === undefined || value.public_key === ''
          ? null
          : Buffer.from(value.public_key, 'base64'),
    }))
    .sort((a, b) => a.version - b.version);
  return {
    name,
    type: data.type,
    derived: data.derived ?? false,
    exportable: data.exportable ?? false,
    deletionAllowed: data.deletion_allowed ?? false,
    latestVersion: data.latest_version,
    minDecryptionVersion: data.min_decryption_version,
    versions,
  };
}
