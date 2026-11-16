export type AiErrorCode =
  /** The runtime cannot be reached or kept failing (network error, 429, 5xx) after every retry. */
  | 'UNAVAILABLE'
  /** No answer within the time limit, after every retry. */
  | 'TIMEOUT'
  /** The runtime refused the API key (401/403). Not retried. */
  | 'UNAUTHORIZED'
  /** The runtime refused the request itself (another 4xx, e.g. context too long). Not retried. */
  | 'REJECTED'
  /** The answer is not the OpenAI shape this client expects, or too large. */
  | 'BAD_RESPONSE'
  /** The model answered, but not with JSON that matches the schema (or it was cut off). */
  | 'INVALID_OUTPUT'
  /** The caller cancelled the request. */
  | 'ABORTED';

/**
 * The only error the AI client throws.
 *
 * Messages are fixed sentences: they never quote the prompt, the document text,
 * the model's output or the runtime's error body (which can echo the prompt).
 */
export class AiError extends Error {
  public readonly code: AiErrorCode;
  /** HTTP status of the last answer, when there was one. */
  public readonly status: number | null;
  public readonly attempts: number;

  public constructor(
    code: AiErrorCode,
    message: string,
    details: {
      readonly status?: number | null;
      readonly attempts?: number;
      readonly cause?: unknown;
    } = {},
  ) {
    super(message, details.cause === undefined ? undefined : { cause: details.cause });
    this.name = 'AiError';
    this.code = code;
    this.status = details.status ?? null;
    this.attempts = details.attempts ?? 1;
  }
}
