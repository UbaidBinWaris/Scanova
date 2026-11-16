import type { LogFields } from '@scanova/config/logger';
import type { AiConfig, LogLevel } from '@scanova/config/sections';
import { z } from 'zod';
import { embeddingInput, toStoredVector, type EmbeddingKind } from './embedding.js';
import { AiError, type AiErrorCode } from './errors.js';

/**
 * Provider-neutral client for an OpenAI-compatible runtime (docs/ARCHITECTURE.md §11).
 *
 * Only the standard request fields are sent (no llama.cpp extensions), so the
 * runtime can be swapped by configuration. Document text is untrusted input:
 * every chat answer is constrained to a JSON schema by the runtime and then
 * validated again here with Zod. Nothing a request carries or an answer
 * contains is ever logged or put into an error message.
 */

/** The method of `AppLogger` this client uses. Fields are ids, names, counts and timings only. */
export interface AiLogger {
  event(level: LogLevel, event: string, fields?: LogFields): void;
}

export interface AiClientOptions {
  readonly logger?: AiLogger;
  /** Per attempt. CPU inference of a one-page document takes up to about a minute. */
  readonly chatTimeoutMs?: number;
  readonly embedTimeoutMs?: number;
  /** Attempts per request, including the first. Only network errors, 408, 429 and 5xx are retried. */
  readonly maxAttempts?: number;
  readonly retryBaseDelayMs?: number;
  /** Inputs per embeddings request. */
  readonly embedBatchSize?: number;
  /** Largest answer body accepted, in bytes. */
  readonly maxResponseBytes?: number;
  /** Test seams. */
  readonly fetch?: typeof fetch;
  readonly sleep?: (ms: number) => Promise<void>;
  readonly now?: () => number;
}

export interface AiImage {
  readonly mediaType: 'image/jpeg' | 'image/png';
  readonly data: Uint8Array;
}

export interface ChatJsonRequest<T> {
  /** Short fixed label for logs, e.g. `document.analyse`. Never content. */
  readonly purpose: string;
  readonly system: string;
  /** The untrusted part: document text, a question. */
  readonly user: string;
  /** Sent before the text (the order vision models are trained on). */
  readonly images?: readonly AiImage[];
  /** What the answer must be. Sent to the runtime as a JSON schema and checked again on return. */
  readonly schema: z.ZodType<T>;
  readonly schemaName: string;
  readonly maxTokens?: number;
  readonly signal?: AbortSignal;
}

export interface AiUsage {
  readonly promptTokens: number | null;
  readonly completionTokens: number | null;
}

export interface ChatJsonResult<T> {
  readonly value: T;
  readonly usage: AiUsage;
  readonly durationMs: number;
}

export interface EmbedRequest {
  readonly purpose: string;
  readonly kind: EmbeddingKind;
  readonly texts: readonly string[];
  readonly signal?: AbortSignal;
}

export interface EmbedResult {
  /** One unit-length vector of `dimensions` values per input, in input order. */
  readonly vectors: readonly Float32Array[];
  /** Recorded with every stored vector, so a model change can trigger a re-embed (§11.5). */
  readonly model: string;
  readonly dimensions: number;
}

export interface AiClient {
  readonly chatModel: string;
  readonly embedModel: string;
  chatJson<T>(request: ChatJsonRequest<T>): Promise<ChatJsonResult<T>>;
  embed(request: EmbedRequest): Promise<EmbedResult>;
}

interface Settings {
  readonly chatTimeoutMs: number;
  readonly embedTimeoutMs: number;
  readonly maxAttempts: number;
  readonly retryBaseDelayMs: number;
  readonly embedBatchSize: number;
  readonly maxResponseBytes: number;
}

const DEFAULTS: Settings = {
  chatTimeoutMs: 180_000,
  embedTimeoutMs: 60_000,
  maxAttempts: 3,
  retryBaseDelayMs: 1_000,
  embedBatchSize: 16,
  maxResponseBytes: 16 * 1024 * 1024,
};

const MAX_RETRY_DELAY_MS = 30_000;
const RETRYABLE_STATUS = new Set([408, 429, 500, 502, 503, 504]);

const chatResponseSchema = z.object({
  choices: z
    .array(
      z.object({
        message: z.object({ content: z.string().nullish() }),
        finish_reason: z.string().nullish(),
      }),
    )
    .min(1),
  usage: z
    .object({
      prompt_tokens: z.number().int().nonnegative().optional(),
      completion_tokens: z.number().int().nonnegative().optional(),
    })
    .nullish(),
});

const embeddingResponseSchema = z.object({
  data: z.array(
    z.object({ index: z.number().int().nonnegative(), embedding: z.array(z.number()) }),
  ),
});

/** Null when AI is switched off: callers check once and never reach a runtime by accident. */
export function createAiClient(config: AiConfig, options: AiClientOptions = {}): AiClient | null {
  if (config.provider === 'none') {
    return null;
  }
  return new OpenAiCompatibleClient(config, options);
}

type RuntimeConfig = Extract<AiConfig, { provider: 'openai-compatible' }>;
type Endpoint = 'chat' | 'embeddings';

class OpenAiCompatibleClient implements AiClient {
  public readonly chatModel: string;
  public readonly embedModel: string;
  private readonly settings: Settings;
  private readonly fetchImpl: typeof fetch;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly now: () => number;

  public constructor(
    private readonly config: RuntimeConfig,
    private readonly options: AiClientOptions,
  ) {
    this.chatModel = config.chat.model;
    this.embedModel = config.embeddings.model;
    this.settings = {
      chatTimeoutMs: options.chatTimeoutMs ?? DEFAULTS.chatTimeoutMs,
      embedTimeoutMs: options.embedTimeoutMs ?? DEFAULTS.embedTimeoutMs,
      maxAttempts: Math.max(1, options.maxAttempts ?? DEFAULTS.maxAttempts),
      retryBaseDelayMs: options.retryBaseDelayMs ?? DEFAULTS.retryBaseDelayMs,
      embedBatchSize: Math.max(1, options.embedBatchSize ?? DEFAULTS.embedBatchSize),
      maxResponseBytes: options.maxResponseBytes ?? DEFAULTS.maxResponseBytes,
    };
    this.fetchImpl = options.fetch ?? fetch;
    this.sleep =
      options.sleep ?? ((ms) => new Promise<void>((resolve) => setTimeout(resolve, ms).unref()));
    this.now = options.now ?? Date.now;
  }

  public async chatJson<T>(request: ChatJsonRequest<T>): Promise<ChatJsonResult<T>> {
    const started = this.now();
    const text = { type: 'text', text: request.user } as const;
    const content =
      request.images === undefined || request.images.length === 0
        ? request.user
        : [
            ...request.images.map((image) => ({
              type: 'image_url' as const,
              image_url: {
                url: `data:${image.mediaType};base64,${Buffer.from(image.data).toString('base64')}`,
              },
            })),
            text,
          ];
    const body = {
      model: this.chatModel,
      messages: [
        { role: 'system', content: request.system },
        { role: 'user', content },
      ],
      temperature: 0,
      ...(request.maxTokens === undefined ? {} : { max_tokens: request.maxTokens }),
      response_format: {
        type: 'json_schema',
        json_schema: {
          name: request.schemaName,
          strict: true,
          schema: jsonSchemaOf(request.schema),
        },
      },
      stream: false,
    };

    const { json, attempts } = await this.post('chat', request.purpose, body, request.signal);
    const parsed = chatResponseSchema.safeParse(json);
    if (!parsed.success) {
      throw this.fail(
        'chat',
        request.purpose,
        'BAD_RESPONSE',
        'The AI runtime sent an unexpected answer.',
        { attempts },
      );
    }
    const [choice] = parsed.data.choices;
    if (choice === undefined) {
      throw this.fail('chat', request.purpose, 'BAD_RESPONSE', 'The AI runtime sent no answer.', {
        attempts,
      });
    }
    const usage: AiUsage = {
      promptTokens: parsed.data.usage?.prompt_tokens ?? null,
      completionTokens: parsed.data.usage?.completion_tokens ?? null,
    };
    if (choice.finish_reason === 'length') {
      throw this.fail('chat', request.purpose, 'INVALID_OUTPUT', 'The model answer was cut off.', {
        attempts,
        usage,
      });
    }
    if (typeof choice.message.content !== 'string') {
      throw this.fail(
        'chat',
        request.purpose,
        'BAD_RESPONSE',
        'The AI runtime sent no answer text.',
        { attempts },
      );
    }
    let value: unknown;
    try {
      value = JSON.parse(unfence(choice.message.content)) as unknown;
    } catch {
      throw this.fail('chat', request.purpose, 'INVALID_OUTPUT', 'The model answer is not JSON.', {
        attempts,
        usage,
      });
    }
    const checked = request.schema.safeParse(value);
    if (!checked.success) {
      // Field paths come from our own schema; the values are never logged.
      const fields = [
        ...new Set(checked.error.issues.map((issue) => issue.path.join('.') || '(root)')),
      ];
      throw this.fail(
        'chat',
        request.purpose,
        'INVALID_OUTPUT',
        'The model answer does not match the schema.',
        {
          attempts,
          usage,
          invalidFields: fields.slice(0, 10).join(','),
        },
      );
    }
    const durationMs = this.now() - started;
    this.options.logger?.event('info', 'ai.chat_completed', {
      purpose: request.purpose,
      model: this.chatModel,
      attempts,
      durationMs,
      promptTokens: usage.promptTokens,
      completionTokens: usage.completionTokens,
      images: request.images?.length ?? 0,
    });
    return { value: checked.data, usage, durationMs };
  }

  public async embed(request: EmbedRequest): Promise<EmbedResult> {
    const { dimensions } = this.config.embeddings;
    const vectors: Float32Array[] = [];
    const started = this.now();
    let attemptsTotal = 0;
    for (let offset = 0; offset < request.texts.length; offset += this.settings.embedBatchSize) {
      const batch = request.texts.slice(offset, offset + this.settings.embedBatchSize);
      const body = {
        model: this.embedModel,
        input: batch.map((text) => embeddingInput(this.embedModel, request.kind, text)),
        encoding_format: 'float',
      };
      const { json, attempts } = await this.post(
        'embeddings',
        request.purpose,
        body,
        request.signal,
      );
      attemptsTotal += attempts;
      const parsed = embeddingResponseSchema.safeParse(json);
      if (!parsed.success || parsed.data.data.length !== batch.length) {
        throw this.fail(
          'embeddings',
          request.purpose,
          'BAD_RESPONSE',
          'The AI runtime sent an unexpected answer.',
          {
            attempts,
          },
        );
      }
      const ordered = [...parsed.data.data].sort((a, b) => a.index - b.index);
      for (const [position, item] of ordered.entries()) {
        const vector = item.index === position ? toStoredVector(item.embedding, dimensions) : null;
        if (vector === null) {
          throw this.fail(
            'embeddings',
            request.purpose,
            'BAD_RESPONSE',
            'The AI runtime sent vectors that do not fit the configured dimensions.',
            { attempts, returnedDimensions: item.embedding.length },
          );
        }
        vectors.push(vector);
      }
    }
    if (request.texts.length > 0) {
      this.options.logger?.event('info', 'ai.embed_completed', {
        purpose: request.purpose,
        model: this.embedModel,
        inputs: request.texts.length,
        attempts: attemptsTotal,
        durationMs: this.now() - started,
      });
    }
    return { vectors, model: this.embedModel, dimensions };
  }

  // ── transport ────────────────────────────────────────────────────────────

  private async post(
    endpoint: Endpoint,
    purpose: string,
    body: unknown,
    signal: AbortSignal | undefined,
  ): Promise<{ readonly json: unknown; readonly attempts: number }> {
    const base = endpoint === 'chat' ? this.config.chat.baseUrl : this.config.embeddings.baseUrl;
    const url = `${base}/${endpoint === 'chat' ? 'chat/completions' : 'embeddings'}`;
    const timeoutMs =
      endpoint === 'chat' ? this.settings.chatTimeoutMs : this.settings.embedTimeoutMs;
    const payload = JSON.stringify(body);
    const { maxAttempts } = this.settings;

    let last: { code: AiErrorCode; status: number | null; retryAfterMs: number | null } = {
      code: 'UNAVAILABLE',
      status: null,
      retryAfterMs: null,
    };
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      if (isAborted(signal)) {
        throw this.fail(endpoint, purpose, 'ABORTED', 'The AI request was cancelled.', {
          attempts: attempt - 1,
        });
      }
      const timeout = AbortSignal.timeout(timeoutMs);
      let response: Response;
      try {
        response = await this.fetchImpl(url, {
          method: 'POST',
          headers: {
            authorization: `Bearer ${this.config.apiKey}`,
            'content-type': 'application/json',
            accept: 'application/json',
          },
          body: payload,
          signal: signal === undefined ? timeout : AbortSignal.any([signal, timeout]),
        });
      } catch {
        if (isAborted(signal)) {
          throw this.fail(endpoint, purpose, 'ABORTED', 'The AI request was cancelled.', {
            attempts: attempt,
          });
        }
        last = {
          code: timeout.aborted ? 'TIMEOUT' : 'UNAVAILABLE',
          status: null,
          retryAfterMs: null,
        };
        await this.beforeRetry(endpoint, purpose, attempt, last);
        continue;
      }

      if (response.ok) {
        const json = await this.readJson(response, endpoint, purpose, attempt, timeout, signal);
        return { json, attempts: attempt };
      }
      // An error body can quote the request (and so the document): it is discarded unread.
      await response.body?.cancel().catch(() => undefined);
      if (response.status === 401 || response.status === 403) {
        throw this.fail(endpoint, purpose, 'UNAUTHORIZED', 'The AI runtime refused the API key.', {
          attempts: attempt,
          status: response.status,
        });
      }
      if (!RETRYABLE_STATUS.has(response.status)) {
        throw this.fail(endpoint, purpose, 'REJECTED', 'The AI runtime refused the request.', {
          attempts: attempt,
          status: response.status,
        });
      }
      last = {
        code: 'UNAVAILABLE',
        status: response.status,
        retryAfterMs: retryAfterMs(response.headers.get('retry-after')),
      };
      await this.beforeRetry(endpoint, purpose, attempt, last);
    }
    const message =
      last.code === 'TIMEOUT'
        ? 'The AI runtime did not answer in time.'
        : 'The AI runtime is unavailable.';
    throw this.fail(endpoint, purpose, last.code, message, {
      attempts: maxAttempts,
      status: last.status,
    });
  }

  private async beforeRetry(
    endpoint: Endpoint,
    purpose: string,
    attempt: number,
    last: { code: AiErrorCode; status: number | null; retryAfterMs: number | null },
  ): Promise<void> {
    if (attempt >= this.settings.maxAttempts) {
      return;
    }
    const backoff = this.settings.retryBaseDelayMs * 2 ** (attempt - 1);
    const delayMs = Math.min(MAX_RETRY_DELAY_MS, Math.max(backoff, last.retryAfterMs ?? 0));
    this.options.logger?.event('warn', 'ai.request_retry', {
      endpoint,
      purpose,
      attempt,
      errorCode: last.code,
      status: last.status,
      delayMs,
    });
    await this.sleep(delayMs);
  }

  private async readJson(
    response: Response,
    endpoint: Endpoint,
    purpose: string,
    attempts: number,
    timeout: AbortSignal,
    signal: AbortSignal | undefined,
  ): Promise<unknown> {
    let text: string;
    try {
      text = await readLimited(response, this.settings.maxResponseBytes);
    } catch (error) {
      if (isAborted(signal)) {
        throw this.fail(endpoint, purpose, 'ABORTED', 'The AI request was cancelled.', {
          attempts,
        });
      }
      if (timeout.aborted) {
        throw this.fail(endpoint, purpose, 'TIMEOUT', 'The AI runtime did not answer in time.', {
          attempts,
        });
      }
      const tooLarge = error instanceof ResponseTooLarge;
      throw this.fail(
        endpoint,
        purpose,
        tooLarge ? 'BAD_RESPONSE' : 'UNAVAILABLE',
        tooLarge
          ? 'The AI runtime sent an answer that is too large.'
          : 'The AI runtime connection failed.',
        { attempts, status: response.status },
      );
    }
    try {
      return JSON.parse(text) as unknown;
    } catch {
      throw this.fail(endpoint, purpose, 'BAD_RESPONSE', 'The AI runtime sent a non-JSON answer.', {
        attempts,
        status: response.status,
      });
    }
  }

  /** Logs the failure (codes and counts only) and returns the error to throw. */
  private fail(
    endpoint: Endpoint,
    purpose: string,
    code: AiErrorCode,
    message: string,
    details: {
      readonly attempts: number;
      readonly status?: number | null;
      readonly usage?: AiUsage;
      readonly invalidFields?: string;
      readonly returnedDimensions?: number;
    },
  ): AiError {
    this.options.logger?.event(code === 'ABORTED' ? 'info' : 'warn', 'ai.request_failed', {
      endpoint,
      purpose,
      model: endpoint === 'chat' ? this.chatModel : this.embedModel,
      errorCode: code,
      status: details.status ?? null,
      attempts: details.attempts,
      promptTokens: details.usage?.promptTokens,
      completionTokens: details.usage?.completionTokens,
      invalidFields: details.invalidFields,
      returnedDimensions: details.returnedDimensions,
    });
    return new AiError(code, message, {
      status: details.status ?? null,
      attempts: details.attempts,
    });
  }
}

/** JSON schema for the runtime's constrained decoding, from the same Zod schema that checks the answer. */
function jsonSchemaOf(schema: z.ZodType): Record<string, unknown> {
  const { $schema: _ignored, ...rest } = z.toJSONSchema(schema, { io: 'output' }) as Record<
    string,
    unknown
  >;
  return rest;
}

/** Tolerates a runtime that wraps the JSON in a Markdown code fence despite `response_format`. */
function unfence(content: string): string {
  const fenced = /^\s*```(?:json)?\s*\n([\s\S]*?)\n\s*```\s*$/u.exec(content);
  return fenced?.[1] ?? content;
}

function retryAfterMs(header: string | null): number | null {
  if (header === null || !/^\d{1,6}$/u.test(header.trim())) {
    return null;
  }
  return Number(header.trim()) * 1000;
}

/** A function, not an inline check: TypeScript would otherwise keep a narrowing across awaits. */
function isAborted(signal: AbortSignal | undefined): boolean {
  return signal?.aborted === true;
}

class ResponseTooLarge extends Error {}

async function readLimited(response: Response, maxBytes: number): Promise<string> {
  const declared = Number(response.headers.get('content-length') ?? '0');
  if (declared > maxBytes) {
    await response.body?.cancel().catch(() => undefined);
    throw new ResponseTooLarge();
  }
  if (response.body === null) {
    return '';
  }
  const chunks: Uint8Array[] = [];
  let size = 0;
  // Node's web streams are async iterable; leaving the loop early cancels the body.
  for await (const chunk of response.body as AsyncIterable<Uint8Array>) {
    size += chunk.byteLength;
    if (size > maxBytes) {
      throw new ResponseTooLarge();
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}
