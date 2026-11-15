import { Agent as HttpAgent, request as httpRequest } from 'node:http';
import { Agent as HttpsAgent, request as httpsRequest } from 'node:https';
import { KmsError } from './errors.js';

export interface VaultHttpClientOptions {
  /** Base address, e.g. `https://openbao:8200`. */
  readonly address: string;
  /** PEM of the CA that signed the vault certificate; null uses the system trust store. */
  readonly caCertificate: string | null;
  readonly timeoutMs: number;
}

export interface VaultResponse {
  readonly status: number;
  /** Parsed JSON body, or null when the body is empty. */
  readonly body: unknown;
}

const MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

/**
 * Minimal JSON-over-HTTP client for the vault API, on Node's own http(s) stack
 * so the internal CA can be pinned per client instead of process-wide.
 * Certificate verification is never disabled.
 */
export class VaultHttpClient {
  private readonly base: URL;
  private readonly agent: HttpAgent | HttpsAgent;
  private readonly secure: boolean;
  private readonly timeoutMs: number;

  public constructor(options: VaultHttpClientOptions) {
    this.base = new URL(options.address);
    this.secure = this.base.protocol === 'https:';
    this.timeoutMs = options.timeoutMs;
    this.agent = this.secure
      ? new HttpsAgent({
          keepAlive: true,
          maxSockets: 16,
          ...(options.caCertificate === null ? {} : { ca: options.caCertificate }),
        })
      : new HttpAgent({ keepAlive: true, maxSockets: 16 });
  }

  public request(
    method: 'GET' | 'POST',
    path: string,
    options: { readonly token?: string; readonly body?: unknown } = {},
  ): Promise<VaultResponse> {
    const payload = options.body === undefined ? null : Buffer.from(JSON.stringify(options.body));
    const headers: Record<string, string> = { accept: 'application/json' };
    if (payload !== null) {
      headers['content-type'] = 'application/json';
      headers['content-length'] = String(payload.length);
    }
    if (options.token !== undefined) {
      headers['x-vault-token'] = options.token;
    }

    return new Promise<VaultResponse>((resolve, reject) => {
      const send = this.secure ? httpsRequest : httpRequest;
      const request = send(
        {
          protocol: this.base.protocol,
          hostname: this.base.hostname,
          port: this.base.port,
          path,
          method,
          headers,
          agent: this.agent,
          timeout: this.timeoutMs,
        },
        (response) => {
          const chunks: Buffer[] = [];
          let size = 0;
          response.on('data', (chunk: Buffer) => {
            size += chunk.length;
            if (size > MAX_RESPONSE_BYTES) {
              request.destroy(new Error('response too large'));
              return;
            }
            chunks.push(chunk);
          });
          response.on('error', (error) => {
            reject(new KmsError('UNAVAILABLE', 'Key vault connection failed.', { cause: error }));
          });
          response.on('end', () => {
            const text = Buffer.concat(chunks).toString('utf8');
            let body: unknown = null;
            if (text.length > 0) {
              try {
                body = JSON.parse(text) as unknown;
              } catch {
                reject(new KmsError('UNEXPECTED_RESPONSE', 'Key vault sent a non-JSON answer.'));
                return;
              }
            }
            resolve({ status: response.statusCode ?? 0, body });
          });
        },
      );
      request.on('timeout', () => {
        request.destroy(new Error('timeout'));
      });
      request.on('error', (error) => {
        // The cause is kept for server-side logs; it holds addresses, never secrets.
        reject(new KmsError('UNAVAILABLE', 'Key vault is unreachable.', { cause: error }));
      });
      if (payload !== null) {
        request.write(payload);
      }
      request.end();
    });
  }

  public close(): void {
    this.agent.destroy();
  }
}
