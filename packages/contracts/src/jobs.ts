import { z } from 'zod';
import { UuidSchema } from './common.js';

/**
 * Background jobs. Payloads carry ids only — never titles, text, e-mail
 * addresses or key material — because the queue is stored in plain PostgreSQL.
 */
export const JOB_NAMES = {
  /** Periodic: start rotations that are due and resume unfinished runs. */
  rotationSweep: 'rotation.sweep',
  /** Execute (or resume) one rotation run. */
  rotationRun: 'rotation.run',
  /** OCR one document's original PDF. */
  documentOcr: 'document.ocr',
  /** Periodic: purge documents past the trash retention. */
  trashPurge: 'trash.purge',
  /** Purge everything of a deleted (crypto-shredded) user. */
  userPurge: 'user.purge',
  /** Periodic: garbage-collect keys whose data never came to exist or is gone. */
  keyGarbageCollect: 'keys.gc',
} as const;

export type JobName = (typeof JOB_NAMES)[keyof typeof JOB_NAMES];

const EmptyPayloadSchema = z.object({}).strict();

export const JOB_PAYLOAD_SCHEMAS = {
  [JOB_NAMES.rotationSweep]: EmptyPayloadSchema,
  [JOB_NAMES.rotationRun]: z.object({ runId: UuidSchema }).strict(),
  [JOB_NAMES.documentOcr]: z.object({ documentId: UuidSchema }).strict(),
  [JOB_NAMES.trashPurge]: EmptyPayloadSchema,
  [JOB_NAMES.userPurge]: z.object({ userId: UuidSchema }).strict(),
  [JOB_NAMES.keyGarbageCollect]: EmptyPayloadSchema,
} as const satisfies Record<JobName, z.ZodType>;

export type JobPayload<Name extends JobName> = z.infer<(typeof JOB_PAYLOAD_SCHEMAS)[Name]>;
