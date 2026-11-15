import { z } from 'zod';
import { ErrorBodySchema, type ErrorBody } from './errors.js';

export const MetaSchema = z.object({
  nextCursor: z.string().nullable(),
});
export type Meta = z.infer<typeof MetaSchema>;

export interface SuccessEnvelope<Data> {
  readonly data: Data;
  readonly error: null;
  readonly meta: Meta;
}

export interface ErrorEnvelope {
  readonly data: null;
  readonly error: ErrorBody;
  readonly meta: Meta;
}

export type Envelope<Data> = SuccessEnvelope<Data> | ErrorEnvelope;

/** Schema of a successful answer carrying `data`. */
export function successEnvelopeSchema<Data extends z.ZodType>(
  data: Data,
): z.ZodObject<{ data: Data; error: z.ZodNull; meta: typeof MetaSchema }> {
  return z.object({ data, error: z.null(), meta: MetaSchema });
}

/** `meta` is optional on errors so a minimal `{ data: null, error }` answer also parses. */
export const ErrorEnvelopeSchema = z.object({
  data: z.null(),
  error: ErrorBodySchema,
  meta: MetaSchema.optional(),
});

export function successEnvelope<Data>(
  data: Data,
  nextCursor: string | null = null,
): SuccessEnvelope<Data> {
  return { data, error: null, meta: { nextCursor } };
}

export function errorEnvelope(error: ErrorBody): ErrorEnvelope {
  return { data: null, error, meta: { nextCursor: null } };
}
