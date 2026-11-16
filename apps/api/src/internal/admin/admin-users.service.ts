import { Inject, Injectable } from '@nestjs/common';
import type {
  AdminCreateUserRequest,
  AdminCreateUserResponse,
  AdminUpdateUserRequest,
} from '@scanova/contracts/admin';
import type { User as UserDto } from '@scanova/contracts/auth';
import { JOB_NAMES } from '@scanova/contracts/jobs';
import { generateTemporaryPassword } from '@scanova/crypto/random';
import type { DatabaseClient, User } from '@scanova/database/client';
import type { JobProducer } from '@scanova/jobs/queue';
import { KeyringError } from '@scanova/keyring/errors';
import type { Keyring } from '@scanova/keyring/keyring';
import { z } from 'zod';
import { AuditService } from '../../audit/audit.service.js';
import { PasswordService } from '../../auth/password.service.js';
import { SessionService } from '../../auth/session.service.js';
import { AppError } from '../../common/app-error.js';
import { decodeCursor, encodeCursor } from '../../common/cursor.js';
import { Page } from '../../common/page.js';
import { AppLogger } from '@scanova/config/logger';
import { DATABASE, JOBS, KEYRING } from '../../platform/tokens.js';
import { UsersService } from '../../users/users.service.js';
import type { Actor } from './actor.js';

const CursorSchema = z.object({ c: z.iso.datetime(), i: z.uuid() });

/** Account administration (§7.4). Used by the admin API and by the operator CLI. */
@Injectable()
export class AdminUsersService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(JOBS) private readonly jobs: JobProducer,
    private readonly users: UsersService,
    private readonly passwords: PasswordService,
    private readonly sessions: SessionService,
    private readonly audit: AuditService,
    private readonly logger: AppLogger,
  ) {}

  /** Oldest account first. Accounts waiting to be purged are not listed. */
  public async list(options: {
    limit: number;
    cursor?: string | undefined;
  }): Promise<Page<UserDto>> {
    const after = options.cursor === undefined ? null : decodeCursor(options.cursor, CursorSchema);
    const rows = await this.database.user.findMany({
      where: {
        deletedAt: null,
        ...(after === null
          ? {}
          : {
              OR: [
                { createdAt: { gt: new Date(after.c) } },
                { createdAt: new Date(after.c), publicId: { gt: after.i } },
              ],
            }),
      },
      orderBy: [{ createdAt: 'asc' }, { publicId: 'asc' }],
      take: options.limit + 1,
    });
    const page = rows.slice(0, options.limit);
    const dtos: UserDto[] = [];
    for (const user of page) {
      try {
        dtos.push(await this.users.toDto(user));
      } catch (error) {
        if (!(error instanceof KeyringError)) {
          throw error;
        }
        // Its keys are gone (a deletion that is still in progress): it is not shown.
        this.logger.event('warn', 'admin.user_keys_missing', { userId: user.publicId });
      }
    }
    const last = page.at(-1);
    return new Page(
      dtos,
      rows.length > options.limit && last !== undefined
        ? encodeCursor({ c: last.createdAt.toISOString(), i: last.publicId })
        : null,
    );
  }

  /** Creates an account with a temporary password that is returned exactly once. */
  public async create(
    actor: Actor,
    request: AdminCreateUserRequest,
  ): Promise<AdminCreateUserResponse> {
    const temporaryPassword = generateTemporaryPassword();
    const user = await this.users.create({
      email: request.email,
      name: request.name,
      userType: request.userType,
      password: temporaryPassword,
      mustChangePassword: true,
    });
    await this.audit.record('USER_CREATED', actor.userId, {
      targetUserId: user.publicId,
      userType: user.userType,
      via: actor.via,
      ip: actor.ip,
    });
    return { user: await this.users.toDto(user), temporaryPassword };
  }

  public async update(
    actor: Actor,
    userPublicId: string,
    request: AdminUpdateUserRequest,
  ): Promise<UserDto> {
    let user = await this.users.getByPublicId(userPublicId);
    const disabling = request.status === 'DISABLED' && user.status !== 'DISABLED';
    const changingType = request.userType !== undefined && request.userType !== user.userType;
    if (disabling || changingType) {
      await this.assertAdministrationSurvives(actor, user);
    }

    if (request.name !== undefined) {
      user = await this.users.updateName(user, request.name);
    }
    const changingQuota =
      request.storageQuotaBytes !== undefined &&
      (request.storageQuotaBytes === null ? null : BigInt(request.storageQuotaBytes)) !==
        user.storageQuotaBytes;
    if (request.storageQuotaBytes !== undefined && changingQuota) {
      // Usage above a lowered quota is kept; only new uploads are refused (QUOTA_EXCEEDED).
      user = await this.database.user.update({
        where: { id: user.id },
        data: {
          storageQuotaBytes:
            request.storageQuotaBytes === null ? null : BigInt(request.storageQuotaBytes),
        },
      });
    }
    if (request.status !== undefined && request.status !== user.status) {
      user = await this.database.user.update({
        where: { id: user.id },
        data: { status: request.status },
      });
      if (request.status === 'DISABLED') {
        await this.sessions.revokeAll(user.id);
      }
    }
    if (request.userType !== undefined && changingType) {
      // Keys first: the user key moves to the other master key and is rotated (§5.2, §5.4).
      const run = await this.keyring.changeUserType(
        user.publicId,
        request.userType,
        actor.userPublicId ?? undefined,
      );
      user = await this.database.user.update({
        where: { id: user.id },
        data: { userType: request.userType },
      });
      // Tokens carry the old type: every session has to sign in again.
      await this.sessions.revokeAll(user.id);
      await this.queueRotation(run.id);
    }
    await this.audit.record('USER_UPDATED', actor.userId, {
      targetUserId: user.publicId,
      nameChanged: request.name !== undefined,
      status: request.status ?? null,
      userType: changingType ? (request.userType ?? null) : null,
      quotaChanged: changingQuota,
      quotaBytes: changingQuota ? (request.storageQuotaBytes ?? null) : null,
      via: actor.via,
      ip: actor.ip,
    });
    return this.users.toDto(user);
  }

  /** New temporary password; every session is revoked and the user key is rotated. */
  public async resetPassword(
    actor: Actor,
    userPublicId: string,
  ): Promise<{ temporaryPassword: string }> {
    const user = await this.users.getByPublicId(userPublicId);
    const temporaryPassword = generateTemporaryPassword();
    await this.database.user.update({
      where: { id: user.id },
      data: {
        passwordHash: await this.passwords.hash(temporaryPassword),
        mustChangePassword: true,
      },
    });
    await this.sessions.revokeAll(user.id);
    const run = await this.keyring.rotation.request({
      scope: 'USER_KEY',
      userId: user.publicId,
      trigger: 'PASSWORD_RESET',
      requestedBy: actor.userPublicId ?? undefined,
    });
    await this.queueRotation(run.id);
    await this.audit.record('USER_PASSWORD_RESET', actor.userId, {
      targetUserId: user.publicId,
      via: actor.via,
      ip: actor.ip,
    });
    return { temporaryPassword };
  }

  public async revokeSessions(actor: Actor, userPublicId: string): Promise<number> {
    const user = await this.users.getByPublicId(userPublicId);
    const revoked = await this.sessions.revokeAll(user.id);
    await this.audit.record('USER_SESSIONS_REVOKED', actor.userId, {
      targetUserId: user.publicId,
      revoked,
      via: actor.via,
      ip: actor.ip,
    });
    return revoked;
  }

  public async setStatus(
    actor: Actor,
    userPublicId: string,
    status: 'ACTIVE' | 'DISABLED',
  ): Promise<UserDto> {
    return this.update(actor, userPublicId, { status });
  }

  /**
   * Crypto-shred and purge (§5.2): the user's keys are destroyed immediately,
   * which makes all their data unrecoverable; the worker then removes the
   * ciphertext objects and rows.
   */
  public async delete(actor: Actor, userPublicId: string): Promise<void> {
    const user = await this.users.getByPublicId(userPublicId);
    await this.assertAdministrationSurvives(actor, user);
    await this.users.shred(user);
    await this.audit.record('USER_DELETED', actor.userId, {
      targetUserId: user.publicId,
      via: actor.via,
      ip: actor.ip,
    });
    await this.jobs.enqueue(JOB_NAMES.userPurge, { userId: user.publicId });
  }

  /** An admin may not lock themselves out, and the last active admin may not be removed. */
  private async assertAdministrationSurvives(actor: Actor, target: User): Promise<void> {
    if (actor.userId !== null && actor.userId === target.id) {
      throw new AppError('FORBIDDEN', {
        message: 'You cannot disable, demote or delete your own account.',
      });
    }
    if (target.userType !== 'ADMIN' || target.status !== 'ACTIVE') {
      return;
    }
    const otherAdmins = await this.database.user.count({
      where: { userType: 'ADMIN', status: 'ACTIVE', deletedAt: null, id: { not: target.id } },
    });
    if (otherAdmins === 0) {
      throw new AppError('FORBIDDEN', {
        message: 'The last active administrator cannot be removed.',
      });
    }
  }

  private async queueRotation(runId: string): Promise<void> {
    await this.jobs.enqueue(JOB_NAMES.rotationRun, { runId }).catch((error: unknown) => {
      // The run is recorded; the worker's sweep picks up any run that has no job.
      this.logger.failure('admin.rotation_not_queued', error, { runId });
    });
  }
}
