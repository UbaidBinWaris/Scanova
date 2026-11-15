import { z } from 'zod';
import { IsoDateTimeSchema, PageQuerySchema, UuidSchema, utf8ByteLength } from './common.js';
import { DocumentStatusSchema, OcrStatusSchema, TextSourceSchema } from './enums.js';

export const MAX_TITLE_LENGTH = 200;
export const MAX_TAGS = 32;
export const MAX_TAG_LENGTH = 64;
export const MAX_PAGE_COUNT = 10_000;
/** `clientOcrText` limit: 1 MiB of UTF-8 (§7.3). */
export const MAX_CLIENT_OCR_TEXT_BYTES = 1_048_576;

export const DocumentTitleSchema = z.string().trim().min(1).max(MAX_TITLE_LENGTH);
export const DocumentTagsSchema = z
  .array(z.string().trim().min(1).max(MAX_TAG_LENGTH))
  .max(MAX_TAGS);

export const DocumentSchema = z.object({
  id: UuidSchema,
  title: z.string(),
  tags: z.array(z.string()),
  pageCount: z.number().int().nonnegative(),
  sizeBytes: z.number().int().nonnegative().nullable(),
  contentSha256: z.string().nullable(),
  status: DocumentStatusSchema,
  ocrStatus: OcrStatusSchema,
  hasSearchablePdf: z.boolean(),
  capturedAt: IsoDateTimeSchema,
  createdAt: IsoDateTimeSchema,
  updatedAt: IsoDateTimeSchema,
  deletedAt: IsoDateTimeSchema.nullable(),
});
export type Document = z.infer<typeof DocumentSchema>;

export const DocumentListSchema = z.array(DocumentSchema);

/** `POST /documents`. The id is generated on the phone so that a retry is idempotent. */
export const CreateDocumentRequestSchema = z.object({
  id: UuidSchema,
  title: DocumentTitleSchema,
  pageCount: z.number().int().min(1).max(MAX_PAGE_COUNT),
  capturedAt: IsoDateTimeSchema,
  tags: DocumentTagsSchema.optional(),
  clientOcrText: z
    .string()
    .refine(
      (value) => utf8ByteLength(value) <= MAX_CLIENT_OCR_TEXT_BYTES,
      'must be at most 1 MiB of UTF-8',
    )
    .optional(),
});
export type CreateDocumentRequest = z.infer<typeof CreateDocumentRequestSchema>;

export const UpdateDocumentRequestSchema = z
  .object({
    title: DocumentTitleSchema.optional(),
    tags: DocumentTagsSchema.optional(),
  })
  .refine((value) => value.title !== undefined || value.tags !== undefined, {
    message: 'Provide a title or tags to change.',
  });
export type UpdateDocumentRequest = z.infer<typeof UpdateDocumentRequestSchema>;

/**
 * `GET /documents`. Without `updatedSince` the newest changes come first; with
 * it, changes arrive oldest first (and include deletions) so a device can resume
 * a sync from the last `updatedAt` it has seen. `updatedSince` is inclusive
 * (`updatedAt >= updatedSince`): a change in the same millisecond is never
 * skipped, at the price of seeing the boundary document again.
 */
export const ListDocumentsQuerySchema = PageQuerySchema.extend({
  updatedSince: IsoDateTimeSchema.optional(),
  q: z.string().trim().min(1).max(200).optional(),
});
export type ListDocumentsQuery = z.infer<typeof ListDocumentsQuerySchema>;

export const FileVariantQuerySchema = z.object({
  variant: z.enum(['original', 'searchable']).default('original'),
});
export type FileVariantQuery = z.infer<typeof FileVariantQuerySchema>;

/** Headers of `PUT /documents/:id/file` (lower-cased by the HTTP layer). */
export const UploadHeadersSchema = z.object({
  'content-length': z
    .string()
    .regex(/^\d{1,15}$/u, 'must be a byte count')
    .transform((value) => Number(value)),
  'x-content-sha256': z
    .string()
    .regex(/^[0-9a-fA-F]{64}$/u, 'must be 64 hex characters')
    .transform((value) => value.toLowerCase()),
});
export type UploadHeaders = z.infer<typeof UploadHeadersSchema>;

export const DocumentTextSchema = z.object({
  text: z.string(),
  source: TextSourceSchema,
});
export type DocumentText = z.infer<typeof DocumentTextSchema>;
