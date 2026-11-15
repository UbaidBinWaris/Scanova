import { Inject, Injectable } from '@nestjs/common';
import { AppLogger } from '@scanova/config/logger';
import { JOB_NAMES } from '@scanova/contracts/jobs';
import type { JobContext, JobProducer } from '@scanova/jobs/queue';
import { KeyringError } from '@scanova/keyring/errors';
import type { Keyring } from '@scanova/keyring/keyring';
import type { RotationProgress } from '@scanova/keyring/types';
import { JOBS, KEYRING } from '../platform/tokens.js';

/**
 * Job handlers for key rotation (§5.4). The engine and the "is it due?" policy
 * live in @scanova/keyring; this only connects them to the queue.
 */
@Injectable()
export class RotationService {
  public constructor(
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(JOBS) private readonly jobs: JobProducer,
    private readonly logger: AppLogger,
  ) {}

  /** Periodic: start what is due and make sure every unfinished run has a job. */
  public async sweep(): Promise<string[]> {
    const { runIds, skippedUsers } = await this.keyring.rotation.sweep();
    for (const runId of runIds) {
      // Deduplicated by run id: a run that already has a job is not queued twice.
      await this.jobs.enqueue(JOB_NAMES.rotationRun, { runId });
    }
    this.logger.info('rotation.sweep', { unfinishedRuns: runIds.length, skippedUsers });
    return runIds;
  }

  /** Executes (or resumes) one run. A run that has to wait is queued again for later. */
  public async run(
    runId: string,
    context: Pick<JobContext, 'isFinalAttempt'>,
  ): Promise<RotationProgress | null> {
    let progress: RotationProgress;
    try {
      progress = await this.keyring.rotation.run(runId);
    } catch (error) {
      if (error instanceof KeyringError && error.code === 'RUN_NOT_FOUND') {
        this.logger.event('warn', 'rotation.run_unknown', { runId });
        return null;
      }
      if (context.isFinalAttempt) {
        const code = (error as { code?: unknown }).code;
        await this.keyring.rotation.fail(runId, typeof code === 'string' ? code : 'ERROR');
        this.logger.failure('rotation.failed', error, { runId });
      }
      throw error;
    }
    if (progress.state === 'WAITING') {
      await this.jobs.enqueue(
        JOB_NAMES.rotationRun,
        { runId },
        { startAfterSeconds: Math.max(1, Math.ceil(progress.retryAfterMs / 1000)) },
      );
    }
    this.logger.info('rotation.run', {
      runId,
      scope: progress.run.scope,
      state: progress.run.state,
      waiting: progress.state === 'WAITING',
      processedItems: progress.run.processedItems,
    });
    return progress;
  }
}
