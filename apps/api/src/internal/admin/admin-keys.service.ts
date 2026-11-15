import { Inject, Injectable } from '@nestjs/common';
import type { CreateRotationRequest, KeyStatus, RotationRun } from '@scanova/contracts/admin';
import { JOB_NAMES } from '@scanova/contracts/jobs';
import type { JobProducer } from '@scanova/jobs/queue';
import { KeyringError } from '@scanova/keyring/errors';
import type { Keyring } from '@scanova/keyring/keyring';
import type { RotationRequest } from '@scanova/keyring/rotation';
import type { RotationRunRecord } from '@scanova/keyring/types';
import { AuditService } from '../../audit/audit.service.js';
import { AppError } from '../../common/app-error.js';
import { Page } from '../../common/page.js';
import { AppLogger } from '@scanova/config/logger';
import { JOBS, KEYRING } from '../../platform/tokens.js';
import { UsersService } from '../../users/users.service.js';
import type { Actor } from './actor.js';

export function toRotationRunDto(run: RotationRunRecord): RotationRun {
  return {
    id: run.id,
    scope: run.scope,
    target: run.target,
    state: run.state,
    trigger: run.trigger,
    fromVersion: run.fromVersion,
    toVersion: run.toVersion,
    totalItems: run.totalItems,
    processedItems: run.processedItems,
    errorCode: run.errorCode,
    createdAt: run.createdAt.toISOString(),
    startedAt: run.startedAt?.toISOString() ?? null,
    finishedAt: run.finishedAt?.toISOString() ?? null,
  };
}

/** Key status and rotation requests (§7.4). The API records and queues; the worker rotates. */
@Injectable()
export class AdminKeysService {
  public constructor(
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(JOBS) private readonly jobs: JobProducer,
    private readonly users: UsersService,
    private readonly audit: AuditService,
    private readonly logger: AppLogger,
  ) {}

  /** Versions, ages and rows pending re-wrap. Never key material. */
  public async status(): Promise<KeyStatus> {
    const report = await this.keyring.status();
    return {
      masterKeys: report.masterKeys.map((key) => ({
        ...key,
        latestVersionCreatedAt: key.latestVersionCreatedAt.toISOString(),
      })),
      userKeys: report.userKeys,
      dataKeys: report.dataKeys,
    };
  }

  public async requestRotation(actor: Actor, request: CreateRotationRequest): Promise<RotationRun> {
    const run = await this.keyring.rotation.request(await this.toEngineRequest(actor, request));
    await this.jobs.enqueue(JOB_NAMES.rotationRun, { runId: run.id }).catch((error: unknown) => {
      // The run is recorded; the worker's sweep picks up any run that has no job.
      this.logger.failure('admin.rotation_not_queued', error, { runId: run.id });
    });
    await this.audit.record('KEY_ROTATION_REQUESTED', actor.userId, {
      runId: run.id,
      scope: run.scope,
      via: actor.via,
      ip: actor.ip,
    });
    return toRotationRunDto(run);
  }

  public async listRotations(options: {
    limit: number;
    cursor?: string | undefined;
  }): Promise<Page<RotationRun>> {
    try {
      const page = await this.keyring.rotation.list(options);
      return new Page(page.runs.map(toRotationRunDto), page.nextCursor);
    } catch (error) {
      if (error instanceof KeyringError && error.code === 'INVALID_STATE') {
        throw new AppError('VALIDATION_FAILED', {
          details: [{ field: 'cursor', message: 'Invalid cursor.' }],
        });
      }
      throw error;
    }
  }

  private async toEngineRequest(
    actor: Actor,
    request: CreateRotationRequest,
  ): Promise<RotationRequest> {
    const common = { trigger: 'MANUAL' as const, requestedBy: actor.userPublicId ?? undefined };
    switch (request.scope) {
      case 'USER_TYPE_MASTER':
        if (request.userType === undefined) {
          throw missing('userType');
        }
        return { scope: 'USER_TYPE_MASTER', userType: request.userType, ...common };
      case 'USER_KEY': {
        if (request.userId === undefined) {
          throw missing('userId');
        }
        // 404 for a user that does not exist, rather than a key error.
        const user = await this.users.getByPublicId(request.userId);
        return { scope: 'USER_KEY', userId: user.publicId, ...common };
      }
      case 'JWT_SIGNING':
        return { scope: 'JWT_SIGNING', ...common };
    }
  }
}

function missing(field: string): AppError {
  return new AppError('VALIDATION_FAILED', {
    details: [{ field, message: 'Required for this scope.' }],
  });
}
