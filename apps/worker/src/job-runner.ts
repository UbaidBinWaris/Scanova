import { Inject, Injectable } from '@nestjs/common';
import { AppLogger } from '@scanova/config/logger';
import type { WorkerConfig } from '@scanova/config/sections';
import { JOB_NAMES } from '@scanova/contracts/jobs';
import type { JobName, JobPayload } from '@scanova/contracts/jobs';
import type { JobContext, JobQueue } from '@scanova/jobs/queue';
import { OcrService } from './ocr/ocr.service.js';
import { JOBS, WORKER_CONFIG } from './platform/tokens.js';
import { PurgeService } from './purge/purge.service.js';
import { RotationService } from './rotation/rotation.service.js';

/** Connects every job to its handler and registers the recurring schedules. */
@Injectable()
export class JobRunner {
  public constructor(
    @Inject(JOBS) private readonly jobs: JobQueue,
    @Inject(WORKER_CONFIG) private readonly config: WorkerConfig,
    private readonly rotation: RotationService,
    private readonly ocr: OcrService,
    private readonly purge: PurgeService,
    private readonly logger: AppLogger,
  ) {}

  public async start(): Promise<void> {
    await this.register(JOB_NAMES.rotationSweep, async () => {
      await this.rotation.sweep();
    });
    await this.register(JOB_NAMES.rotationRun, async (payload, context) => {
      await this.rotation.run(payload.runId, context);
    });
    await this.register(
      JOB_NAMES.documentOcr,
      async (payload, context) => {
        await this.ocr.process(payload.documentId, context);
      },
      { concurrency: this.config.ocr.concurrency },
    );
    await this.register(JOB_NAMES.trashPurge, async () => {
      await this.purge.purgeTrash();
      await this.purge.housekeeping();
    });
    await this.register(JOB_NAMES.userPurge, async (payload) => {
      await this.purge.purgeUser(payload.userId);
    });
    await this.register(JOB_NAMES.keyGarbageCollect, async () => {
      await this.purge.collectOrphanKeys();
    });
    await this.jobs.scheduleRecurring();
    // Catch up at once on what a restart may have missed, instead of waiting for the next cron slot.
    await this.jobs.enqueue(JOB_NAMES.rotationSweep, {});
    await this.purge.requeueStaleOcr();
    this.logger.info('worker.jobs_registered', { ocrConcurrency: this.config.ocr.concurrency });
  }

  /**
   * Registers a handler and makes every failed attempt visible: the queue retries
   * silently, so without this a job that keeps failing would leave no trace until
   * its last attempt. Only the error name and code are logged.
   */
  private register<Name extends JobName>(
    name: Name,
    handler: (payload: JobPayload<Name>, context: JobContext) => Promise<void>,
    options: { readonly concurrency?: number } = {},
  ): Promise<void> {
    return this.jobs.work(
      name,
      async (payload, context) => {
        try {
          await handler(payload, context);
        } catch (error) {
          this.logger.failure('job.attempt_failed', error, {
            job: name,
            jobId: context.id,
            attempt: context.attempt,
            finalAttempt: context.isFinalAttempt,
          });
          throw error;
        }
      },
      options,
    );
  }
}
