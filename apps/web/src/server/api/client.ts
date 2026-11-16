import { ErrorEnvelopeSchema, MetaSchema } from '@scanova/contracts/envelope';
import { ERROR_DEFINITIONS, type ErrorCode } from '@scanova/contracts/errors';
import { z } from 'zod';

/** API error codes (§7.5) plus the two the web server itself can observe. */
export type ApiErrorCode = ErrorCode | 'NETWORK_ERROR' | 'BAD_RESPONSE';

export interface ApiFailure {
  readonly ok: false;
  readonly status: number;
  readonly code: ApiErrorCode;
  /** User-facing text: the API's own message, which never quotes input. */
  readonly message: string;
  readonly details?: unknown;
  readonly retryAfterSeconds?: number;
}

export interface ApiSuccess<T> {
  readonly ok: true;
  readonly status: number;
  readonly data: T;
  readonly nextCursor: string | null;
}

export type ApiResult<T> = ApiSuccess<T> | ApiFailure;

/** Who the call is made for. The service token is added by the client, never by callers. */
export interface CallContext {
  readonly accessToken?: string | null;
  /** The browser's address, forwarded so the API's rate limits and audit log see it. */
  readonly clientIp?: string | null;
}

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

export interface ApiRequest<Schema extends z.ZodType> {
  readonly method?: HttpMethod;
  readonly path: string;
  readonly query?: Readonly<Record<string, string | number | undefined>>;
  readonly body?: unknown;
  /** Schema of `data` in the success envelope. */
  readonly schema: Schema;
}

export interface ApiClientOptions {
  /** Private-network base URL, e.g. `http://api:3000`. */
  readonly apiUrl: string;
  readonly serviceToken: string;
  readonly fetch?: typeof fetch;
  readonly timeoutMs?: number;
}

export const SERVICE_TOKEN_HEADER = 'x-scanova-service-token';

/**
 * Exactly the shape the edge would forward plus the internal prefixes: ids are
 * UUIDs, so no caller can smuggle `..`, `?` or an encoded slash into a path.
 */
const API_PATH = /^\/(?:api\/v1|internal\/(?:web|admin)\/v1)(?:\/[A-Za-z0-9_-]+)+$/u;

/** The envelope of a success (§7.1); `data` is then checked with the operation's schema. */
const SuccessEnvelopeShape = z.object({ data: z.unknown(), error: z.null(), meta: MetaSchema });

const NETWORK_MESSAGE = 'The server is not reachable right now. Try again.';
const BAD_RESPONSE_MESSAGE = ERROR_DEFINITIONS.INTERNAL_ERROR.message;

export function failure(
  status: number,
  code: ApiErrorCode,
  message: string,
  extra: { details?: unknown; retryAfterSeconds?: number } = {},
): ApiFailure {
  return { ok: false, status, code, message, ...extra };
}

/**
 * The web server's only way to the API. Server-side only: it holds the
 * service token, and callers pass the user's access token per call.
 * Every answer is validated against the contract schemas before use.
 */
export class ApiClient {
  private readonly fetchImpl: typeof fetch;
  private readonly timeoutMs: number;

  public constructor(private readonly options: ApiClientOptions) {
    this.fetchImpl = options.fetch ?? fetch;
    this.timeoutMs = options.timeoutMs ?? 15_000;
  }

  public async request<Schema extends z.ZodType>(
    context: CallContext,
    request: ApiRequest<Schema>,
  ): Promise<ApiResult<z.output<Schema>>> {
    const response = await this.send(context, request.method ?? 'GET', request.path, {
      query: request.query,
      body: request.body,
    });
    if (!(response instanceof Response)) {
      return response;
    }
    const json = await readJson(response);
    if (response.ok) {
      const envelope = SuccessEnvelopeShape.safeParse(json);
      const data = envelope.success ? request.schema.safeParse(envelope.data.data) : null;
      return envelope.success && data?.success === true
        ? {
            ok: true,
            status: response.status,
            data: data.data,
            nextCursor: envelope.data.meta.nextCursor,
          }
        : failure(502, 'BAD_RESPONSE', BAD_RESPONSE_MESSAGE);
    }
    return toFailure(response, json);
  }

  /**
   * A streamed, not enveloped answer (PDF and JPEG downloads). The body is
   * handed on untouched; a failure is parsed like any other.
   */
  public async stream(
    context: CallContext,
    path: string,
    options: { query?: Readonly<Record<string, string | undefined>>; signal?: AbortSignal } = {},
  ): Promise<Response | ApiFailure> {
    const response = await this.send(context, 'GET', path, {
      query: options.query,
      signal: options.signal,
      streaming: true,
    });
    if (!(response instanceof Response) || response.ok) {
      return response;
    }
    return toFailure(response, await readJson(response));
  }

  private async send(
    context: CallContext,
    method: HttpMethod,
    path: string,
    options: {
      query?: Readonly<Record<string, string | number | undefined>> | undefined;
      body?: unknown;
      signal?: AbortSignal | undefined;
      streaming?: boolean;
    },
  ): Promise<Response | ApiFailure> {
    if (!API_PATH.test(path)) {
      throw new Error('Refusing an API path outside the contract.');
    }
    const url = new URL(`${this.options.apiUrl}${path}`);
    for (const [name, value] of Object.entries(options.query ?? {})) {
      if (value !== undefined) {
        url.searchParams.set(name, String(value));
      }
    }
    const headers = new Headers({ accept: 'application/json' });
    if (options.body !== undefined) {
      headers.set('content-type', 'application/json');
    }
    if (context.accessToken !== undefined && context.accessToken !== null) {
      headers.set('authorization', `Bearer ${context.accessToken}`);
    }
    if (path.startsWith('/internal/')) {
      headers.set(SERVICE_TOKEN_HEADER, this.options.serviceToken);
    }
    if (context.clientIp !== undefined && context.clientIp !== null) {
      headers.set('x-forwarded-for', context.clientIp);
    }
    // Downloads may take long; only the wait for the response head is bounded.
    const timeout = AbortSignal.timeout(this.timeoutMs);
    const signal =
      options.signal === undefined ? timeout : AbortSignal.any([options.signal, timeout]);
    try {
      return await this.fetchImpl(url, {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        cache: 'no-store',
        redirect: 'error',
        signal: options.streaming === true ? (options.signal ?? null) : signal,
      });
    } catch {
      return failure(503, 'NETWORK_ERROR', NETWORK_MESSAGE);
    }
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return (await response.json()) as unknown;
  } catch {
    return null;
  }
}

function toFailure(response: Response, json: unknown): ApiFailure {
  const parsed = ErrorEnvelopeSchema.safeParse(json);
  const retryAfter = Number(response.headers.get('retry-after'));
  const extra = {
    ...(Number.isFinite(retryAfter) && retryAfter > 0 ? { retryAfterSeconds: retryAfter } : {}),
  };
  if (!parsed.success) {
    return failure(response.status, 'BAD_RESPONSE', BAD_RESPONSE_MESSAGE, extra);
  }
  const { code, message, details } = parsed.data.error;
  return failure(response.status, code, message, {
    ...extra,
    ...(details === undefined ? {} : { details }),
  });
}
