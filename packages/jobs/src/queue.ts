import { toPgConnectionOptions } from '@scanova/config/postgres';
import type { PostgresConfig } from '@scanova/config/sections';
import type { JobName, JobPayload } from '@scanova/contracts/jobs';
import { PgBoss } from 'pg-boss';
import { JOB_DEFINITIONS, JOB_NAME_LIST, JOB_SCHEMA, parseJobPayload } from './definitions.js';

export interface EnqueueOptions {
  /** Do not start before this many seconds have passed. */
  readonly startAfterSeconds?: number;
}

/** What code that only produces jobs depends on (the API). */
export interface JobProducer {
  /**
   * Queues a job. Returns its id, or null when the same work is already queued
   * (jobs are deduplicated by the id in their payload).
   */
  enqueue<Name extends JobName>(
    name: Name,
    payload: JobPayload<Name>,
    options?: EnqueueOptions,
  ): Promise<string | null>;
}

export interface JobContext {
  readonly id: string;
  /** 0 on the first attempt. */
  readonly attempt: number;
  /** True when a failure now will not be retried: the handler should record the failure. */
  readonly isFinalAttempt: boolean;
  /** Aborted when the job expires or the worker shuts down. */
  readonly signal: AbortSignal;
}

export type JobHandler<Name extends JobName> = (
  payload: JobPayload<Name>,
  context: JobContext,
) => Promise<void>;

export interface JobQueueOptions {
  /**
   * `producer` only sends jobs (the API). `worker` also runs maintenance and
   * fires the cron schedules; exactly the worker process does that.
   */
  readonly role: 'producer' | 'worker';
  /** Called for queue-level errors (lost connection, …). Never receives payloads. */
  readonly onError: (error: Error) => void;
  readonly maxConnections?: number;
}

/**
 * Typed queue client on pg-boss. The queue lives in the main database, in its
 * own schema, and is installed by the migrate step: at runtime this client
 * neither migrates nor creates anything, so it works with the row-level
 * runtime role.
 */
export class JobQueue implements JobProducer {
  private readonly boss: PgBoss;
  private readonly role: JobQueueOptions['role'];
  private started = false;

  public constructor(config: PostgresConfig, options: JobQueueOptions) {
    this.role = options.role;
    this.boss = new PgBoss({
      ...toPgConnectionOptions(config),
      application_name: `scanova-jobs-${options.role}`,
      schema: JOB_SCHEMA,
      max: options.maxConnections ?? 4,
      // Installed by the migrator; the runtime role has no DDL rights.
      migrate: false,
      createSchema: false,
      reindex: false,
      monitorVacuum: false,
      supervise: options.role === 'worker',
      schedule: options.role === 'worker',
    });
    this.boss.on('error', options.onError);
  }

  public async start(): Promise<void> {
    if (!this.started) {
      await this.boss.start();
      this.started = true;
    }
  }

  /** Waits for running handlers to finish, then closes the connections. */
  public async stop(): Promise<void> {
    if (this.started) {
      this.started = false;
      await this.boss.stop({ graceful: true, timeout: 30_000 });
    }
  }

  public async enqueue<Name extends JobName>(
    name: Name,
    payload: JobPayload<Name>,
    options: EnqueueOptions = {},
  ): Promise<string | null> {
    const definition = JOB_DEFINITIONS[name];
    // Validated on the way in as well: nothing but ids may enter the queue.
    const data = parseJobPayload(name, payload);
    const dedupeKey = definition.dedupeKey === null ? null : definition.dedupeKey(data);
    return this.boss.send(name, data, {
      ...(dedupeKey === null ? {} : { singletonKey: dedupeKey }),
      ...(options.startAfterSeconds === undefined ? {} : { startAfter: options.startAfterSeconds }),
    });
  }

  /**
   * Registers a handler. Payloads are validated before the handler sees them; a
   * payload that does not match its schema fails the job.
   */
  public async work<Name extends JobName>(
    name: Name,
    handler: JobHandler<Name>,
    options: { readonly concurrency?: number; readonly pollingIntervalSeconds?: number } = {},
  ): Promise<void> {
    const definition = JOB_DEFINITIONS[name];
    await this.boss.work<unknown>(
      name,
      {
        batchSize: 1,
        localConcurrency: options.concurrency ?? 1,
        pollingIntervalSeconds: options.pollingIntervalSeconds ?? 2,
      },
      async (jobs) => {
        for (const job of jobs) {
          await handler(parseJobPayload(name, job.data), {
            id: job.id,
            attempt: job.retryCount,
            isFinalAttempt: job.retryCount >= definition.retryLimit,
            signal: job.signal,
          });
        }
      },
    );
  }

  /** Registers the cron schedule of every recurring job. Idempotent. Worker only. */
  public async scheduleRecurring(): Promise<void> {
    for (const name of JOB_NAME_LIST) {
      const definition = JOB_DEFINITIONS[name];
      if (definition.cron !== null) {
        await this.boss.schedule(name, definition.cron, {}, { tz: 'UTC' });
      }
    }
  }

  /** Resolves when the queue schema is installed and reachable. */
  public async health(): Promise<void> {
    if (!(await this.boss.isInstalled())) {
      throw new Error('Job queue schema is not installed. Run the migrate step.');
    }
  }

  public get isWorker(): boolean {
    return this.role === 'worker';
  }
}
