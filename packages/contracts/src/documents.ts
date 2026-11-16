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
  /**
   * Last change to the page list or to a page's original; null when the document
   * has no page list on the server. A device that keeps pages compares it with
   * the value it last applied to know when to fetch `GET …/pages` again.
   */
  pagesUpdatedAt: IsoDateTimeSchema.nullable(),
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

// ── Pages (§7.3): each page's original photo and the recipe that makes the PDF page ──

/** A page recipe, serialised as JSON, may be at most this large (64 KiB of UTF-8). */
export const MAX_PAGE_EDIT_BYTES = 65_536;
/** Upper bound of a page image's width or height in pixels. */
export const MAX_PAGE_DIMENSION = 100_000;
/** `PUT …/pages` bodies may be larger than other JSON bodies: many pages, each with a recipe. */
export const MAX_PAGE_LIST_BODY_BYTES = 8 * 1024 * 1024;

const PagePointSchema = z.looseObject({ x: z.number(), y: z.number() });

const MarkupStrokeSchema = z.looseObject({
  /** `pen` or `highlighter` today; newer tools are accepted. */
  tool: z.string().min(1).max(32),
  /** ARGB colour as a number, e.g. 4280180479 (0xFF1E5EFF). */
  color: z.number().int().min(0).max(0xffffffff),
  /** Stroke width as a fraction of the page width. */
  width: z.number().nonnegative(),
  points: z.array(PagePointSchema),
});

/**
 * A curled page's mesh (the phone's `PageMesh`): `rows` × `cols` points, row by
 * row from the top-left, each where that spot of the flat page lies in the
 * upright original. `points` is Base64 of little-endian unsigned 16-bit x, y per
 * point, each a fraction (0..1) × 65535, so at most 64 × 64 × 4 bytes.
 */
export const PageMeshSchema = z
  .looseObject({
    rows: z.number().int().min(2).max(64),
    cols: z.number().int().min(2).max(64),
    points: z
      .string()
      .max(21_848)
      .regex(/^[A-Za-z0-9+/]*={0,2}$/u),
  })
  .refine((mesh) => mesh.points.length === 4 * Math.ceil((mesh.rows * mesh.cols * 4) / 3), {
    message: 'points must hold exactly rows × cols x,y pairs',
    path: ['points'],
  });

/**
 * How a page's current image is made from its original (the phone's `PageEdit`):
 * upright the original (`sourceRotation`) → flatten the page: along its
 * `flatten` mesh when it has one (a curled page), otherwise the `crop` outline
 * (four corners in fractions of the upright original, or null for the whole
 * image) → turn by `rotation` → `filter` at `filterStrength` →
 * `brightness`/`contrast` → draw `markup`. Checked loosely: known fields must
 * have the right type, unknown fields are kept as they are, so newer recipes
 * pass through untouched.
 */
export const PageEditSchema = z.looseObject({
  /** Recipe format version. */
  v: z.number().int().min(1),
  sourceRotation: z.number().int().optional(),
  crop: z.array(PagePointSchema).length(4).nullable().optional(),
  rotation: z.number().int().optional(),
  filter: z.string().max(64).optional(),
  filterStrength: z.number().optional(),
  brightness: z.number().optional(),
  contrast: z.number().optional(),
  markup: z.array(MarkupStrokeSchema).optional(),
  flatten: PageMeshSchema.nullable().optional(),
  /** How the app laid the page out (`id_card`); absent for a photographed page. */
  layout: z.string().max(32).nullable().optional(),
});
export type PageEdit = z.infer<typeof PageEditSchema>;

/** One page of `PUT …/pages`. `edit` may be null or absent when the phone has no recipe. */
export const PageInputSchema = z
  .object({
    id: UuidSchema,
    /** Order of the page; any distinct non-negative numbers, stored as 0, 1, 2, … */
    position: z.number().int().min(0).max(1_000_000),
    width: z.number().int().min(1).max(MAX_PAGE_DIMENSION),
    height: z.number().int().min(1).max(MAX_PAGE_DIMENSION),
    edit: PageEditSchema.nullish(),
  })
  .refine(
    (page) =>
      page.edit === null ||
      page.edit === undefined ||
      utf8ByteLength(JSON.stringify(page.edit)) <= MAX_PAGE_EDIT_BYTES,
    { message: 'must be at most 64 KiB of JSON', path: ['edit'] },
  );
export type PageInput = z.infer<typeof PageInputSchema>;

/**
 * `PUT /documents/:id/pages`: the complete page list, in any order. Pages not in
 * the list are deleted together with their originals. Sending the same list again
 * changes nothing.
 */
export const ReplacePagesRequestSchema = z
  .object({ pages: z.array(PageInputSchema).min(1).max(MAX_PAGE_COUNT) })
  .superRefine((request, context) => {
    const ids = new Set<string>();
    const positions = new Set<number>();
    request.pages.forEach((page, index) => {
      if (ids.has(page.id)) {
        context.addIssue({
          code: 'custom',
          message: 'duplicate page id',
          path: ['pages', index, 'id'],
        });
      }
      if (positions.has(page.position)) {
        context.addIssue({
          code: 'custom',
          message: 'duplicate position',
          path: ['pages', index, 'position'],
        });
      }
      ids.add(page.id);
      positions.add(page.position);
    });
  });
export type ReplacePagesRequest = z.infer<typeof ReplacePagesRequestSchema>;

export const DocumentPageSchema = z.object({
  id: UuidSchema,
  position: z.number().int().nonnegative(),
  width: z.number().int().positive(),
  height: z.number().int().positive(),
  /** The decrypted recipe, or null when the phone recorded none. */
  edit: PageEditSchema.nullable(),
  /** The original photo is stored (`GET …/pages/:pageId/original`). */
  hasOriginal: z.boolean(),
  /** SHA-256 (lower-case hex) and size of the original's plaintext. */
  originalSha256: z.string().nullable(),
  originalSizeBytes: z.number().int().nonnegative().nullable(),
  createdAt: IsoDateTimeSchema,
  /** Last change to this page: the same instant as the document's `pagesUpdatedAt` for that change. */
  updatedAt: IsoDateTimeSchema,
});
export type DocumentPage = z.infer<typeof DocumentPageSchema>;

/** `GET` and `PUT /documents/:id/pages`: the page list in order. */
export const DocumentPagesSchema = z.object({
  pagesUpdatedAt: IsoDateTimeSchema.nullable(),
  pages: z.array(DocumentPageSchema),
});
export type DocumentPages = z.infer<typeof DocumentPagesSchema>;

export const PageParamSchema = z.object({ id: UuidSchema, pageId: UuidSchema });
export type PageParam = z.infer<typeof PageParamSchema>;
