import { z } from 'zod';

/** Public id of any row. Normalised to lower case so comparisons are exact. */
export const UuidSchema = z.uuid().transform((value) => value.toLowerCase());

/** ISO-8601 instant in UTC or with an explicit offset. */
export const IsoDateTimeSchema = z.iso.datetime({ offset: true });

export const SuccessSchema = z.object({ success: z.literal(true) });
export type Success = z.infer<typeof SuccessSchema>;

export const DEFAULT_PAGE_SIZE = 50;
export const MAX_PAGE_SIZE = 200;

/** `?limit=50&cursor=…`. Query values arrive as strings, hence the coercion. */
export const PageQuerySchema = z.object({
  limit: z.coerce.number().int().min(1).max(MAX_PAGE_SIZE).default(DEFAULT_PAGE_SIZE),
  cursor: z.string().min(1).max(512).optional(),
});
export type PageQuery = z.infer<typeof PageQuerySchema>;

export const IdParamSchema = z.object({ id: UuidSchema });
export type IdParam = z.infer<typeof IdParamSchema>;

/** UTF-8 size of a string, without depending on Node's Buffer. */
export function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}
