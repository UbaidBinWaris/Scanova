import {
  JOB_NAMES,
  JOB_PAYLOAD_SCHEMAS,
  type JobName,
  type JobPayload,
} from '@scanova/contracts/jobs';

/** PostgreSQL schema that holds the queue, inside the main database. */
export const JOB_SCHEMA = 'pgboss';

export interface JobDefinition<Name extends JobName> {
  readonly name: Name;
  /** How many times a failed job is retried before it is given up. */
  readonly retryLimit: number;
  /** Seconds before the first retry; later retries back off exponentially. */
  readonly retryDelaySeconds: number;
  /** A job running longer than this is considered lost and is retried. */
  readonly expireInSeconds: number;
  /**
   * At most one queued and one running job per key. Enqueueing the same work
   * twice is a no-op, while a running job can still queue its own follow-up.
   */
  readonly dedupeKey: ((payload: JobPayload<Name>) => string) | null;
  /** Cron expression (UTC) for recurring jobs. */
  readonly cron: string | null;
}

type Definitions = { readonly [Name in JobName]: JobDefinition<Name> };

/**
 * Every background job, in one place. Payload shapes live in
 * `@scanova/contracts/jobs`; they carry ids only.
 */
export const JOB_DEFINITIONS: Definitions = {
  [JOB_NAMES.rotationSweep]: {
    name: JOB_NAMES.rotationSweep,
    retryLimit: 1,
    retryDelaySeconds: 60,
    expireInSeconds: 15 * 60,
    dedupeKey: () => 'sweep',
    cron: '17 * * * *',
  },
  [JOB_NAMES.rotationRun]: {
    name: JOB_NAMES.rotationRun,
    retryLimit: 5,
    retryDelaySeconds: 30,
    expireInSeconds: 60 * 60,
    dedupeKey: (payload) => payload.runId,
    cron: null,
  },
  [JOB_NAMES.documentOcr]: {
    name: JOB_NAMES.documentOcr,
    // One retry only: a PDF that times out would otherwise hold the single OCR slot for 90 minutes.
    retryLimit: 1,
    retryDelaySeconds: 60,
    expireInSeconds: 45 * 60,
    dedupeKey: (payload) => payload.documentId,
    cron: null,
  },
  [JOB_NAMES.trashPurge]: {
    name: JOB_NAMES.trashPurge,
    retryLimit: 1,
    retryDelaySeconds: 300,
    expireInSeconds: 60 * 60,
    dedupeKey: () => 'purge',
    cron: '41 3 * * *',
  },
  [JOB_NAMES.userPurge]: {
    name: JOB_NAMES.userPurge,
    retryLimit: 5,
    retryDelaySeconds: 60,
    expireInSeconds: 60 * 60,
    dedupeKey: (payload) => payload.userId,
    cron: null,
  },
  [JOB_NAMES.keyGarbageCollect]: {
    name: JOB_NAMES.keyGarbageCollect,
    retryLimit: 1,
    retryDelaySeconds: 300,
    expireInSeconds: 60 * 60,
    dedupeKey: () => 'gc',
    cron: '23 4 * * *',
  },
};

export const JOB_NAME_LIST = Object.values(JOB_NAMES);

/** Validates a payload against its job's schema. Throws a ZodError for anything but ids. */
export function parseJobPayload<Name extends JobName>(
  name: Name,
  payload: unknown,
): JobPayload<Name> {
  return JOB_PAYLOAD_SCHEMAS[name].parse(payload) as JobPayload<Name>;
}
