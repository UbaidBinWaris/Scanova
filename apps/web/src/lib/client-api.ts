import { ErrorEnvelopeSchema, MetaSchema } from '@scanova/contracts/envelope';
import { z } from 'zod';
import { PATHS, type Surface } from './surface';

/** A failed call from the browser to one of the web app's own JSON routes. */
export class ClientApiError extends Error {
  public constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'ClientApiError';
  }
}

const SuccessEnvelopeShape = z.object({ data: z.unknown(), error: z.null(), meta: MetaSchema });

const SESSION_ENDED = new Set(['UNAUTHENTICATED', 'SESSION_REVOKED', 'ACCOUNT_DISABLED']);

export interface Envelope<T> {
  readonly data: T;
  readonly nextCursor: string | null;
}

/**
 * GETs one of the web app's same-origin JSON routes (never the API: the
 * browser holds no token) and validates the answer with the contract schema.
 * A dead session sends the browser to sign in again.
 */
export async function fetchEnvelope<Schema extends z.ZodType>(
  url: string,
  schema: Schema,
  surface: Surface,
  signal?: AbortSignal,
): Promise<Envelope<z.output<Schema>>> {
  let response: Response;
  try {
    response = await fetch(url, {
      headers: { accept: 'application/json' },
      cache: 'no-store',
      credentials: 'same-origin',
      ...(signal === undefined ? {} : { signal }),
    });
  } catch (error) {
    if (signal?.aborted === true) {
      throw error;
    }
    throw new ClientApiError(0, 'NETWORK_ERROR', 'You appear to be offline. Try again.');
  }
  const json: unknown = await response.json().catch(() => null);
  if (response.ok) {
    const envelope = SuccessEnvelopeShape.safeParse(json);
    const data = envelope.success ? schema.safeParse(envelope.data.data) : null;
    if (envelope.success && data?.success === true) {
      return { data: data.data, nextCursor: envelope.data.meta.nextCursor };
    }
    throw new ClientApiError(502, 'BAD_RESPONSE', 'Something went wrong. Try again.');
  }
  const failure = ErrorEnvelopeSchema.safeParse(json);
  const code = failure.success ? failure.data.error.code : 'BAD_RESPONSE';
  if (response.status === 401 || SESSION_ENDED.has(code)) {
    window.location.assign(PATHS[surface].expired);
  } else if (code === 'PASSWORD_CHANGE_REQUIRED') {
    window.location.assign(PATHS[surface].password);
  }
  throw new ClientApiError(
    response.status,
    code,
    failure.success ? failure.data.error.message : 'Something went wrong. Try again.',
  );
}
