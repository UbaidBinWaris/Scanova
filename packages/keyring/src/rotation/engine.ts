import type { KeyNamesConfig, RotationConfig } from '@scanova/config/sections';
import { unwrapDataKey, wrapDataKey } from '@scanova/crypto/key-wrap';
import { randomKey } from '@scanova/crypto/random';
import { zeroize } from '@scanova/crypto/zeroize';
import type { KeyRotationRun, KeystoreClient } from '@scanova/keystore/client';
import { ciphertextKeyVersion, type Kms } from '@scanova/kms/kms';
import { KeyringError } from '../errors.js';
import { isRotationDue } from '../rotation-policy.js';
import type { RotationProgress, RotationRunRecord, RotationTrigger, UserKind } from '../types.js';
import type { UserKeys } from '../user-keys.js';
import {
  findUnfinishedRun,
  listRuns,
  loadRun,
  lockTarget,
  toRunRecord,
  type RunPage,
} from './runs.js';

export type RotationRequest =
  | {
      readonly scope: 'USER_TYPE_MASTER';
      readonly userType: UserKind;
      readonly trigger: RotationTrigger;
      readonly requestedBy?: string | undefined;
    }
  | {
      readonly scope: 'USER_KEY';
      readonly userId: string;
      readonly trigger: RotationTrigger;
      readonly requestedBy?: string | undefined;
    }
  | {
      readonly scope: 'JWT_SIGNING';
      readonly trigger: RotationTrigger;
      readonly requestedBy?: string | undefined;
    };

export interface RotationEngineSettings {
  readonly keys: KeyNamesConfig;
  readonly rotation: RotationConfig;
  readonly accessTokenTtlSeconds: number;
  readonly masterKeySettleSeconds: number;
  readonly signingKeyRetireMarginSeconds: number;
  readonly now: () => Date;
}

/** Upper bound of user keys looked at per sweep, oldest first. The rest follow next sweep. */
const SWEEP_USER_KEY_LIMIT = 500;

/**
 * The rotation engine (§5.4). Every rotation is a row in `key_rotation_runs`,
 * and all work is derived from what the key database says is still under an old
 * version — so a run can be executed, crash, and be executed again any number of
 * times with the same result.
 *
 * - USER_TYPE_MASTER: new Transit version → re-wrap every wrapped user key in
 *   the vault, in batches → raise `min_decryption_version`.
 * - USER_KEY: new user-key version becomes active → re-wrap that user's data
 *   keys in batches, inside the key database → destroy the old version. The
 *   main database is never read or written and no content is re-encrypted.
 * - JWT_SIGNING: new Transit version signs → the old one is retired once the
 *   longest-lived access token has expired.
 *
 * Whether a key is due is not decided here: that is `isRotationDue`.
 */
export class RotationEngine {
  public constructor(
    private readonly keystore: KeystoreClient,
    private readonly kms: Kms,
    private readonly userKeys: UserKeys,
    private readonly settings: RotationEngineSettings,
  ) {}

  /**
   * Records a rotation to be carried out by {@link run}. Asking again while one
   * is unfinished for the same target returns that one. For `USER_KEY` the new
   * version becomes active immediately (the API can do that part itself, e.g.
   * on a password reset); the re-wrap is left to the worker.
   */
  public async request(request: RotationRequest): Promise<RotationRunRecord> {
    if (request.scope === 'USER_KEY') {
      return this.beginUserKeyRotation(request.userId, request.trigger, request.requestedBy);
    }
    const target =
      request.scope === 'JWT_SIGNING'
        ? this.settings.keys.jwt
        : this.userKeys.masterKeyFor(request.userType);
    return this.createVaultRun(request.scope, target, request.trigger, request.requestedBy, null);
  }

  /**
   * Works on a run until it is finished or has to wait (for the settle time of
   * a master key, or the token lifetime of a signing key). Safe to call again
   * at any time, also after a crash and also concurrently.
   */
  public async run(runId: string): Promise<RotationProgress> {
    const run = await loadRun(this.keystore, runId);
    // A failed run is never executed again. Its target may have been superseded by
    // a newer rotation, and finishing it late could destroy the version everything
    // now depends on. The sweep starts a fresh run for whatever is left instead.
    if (run.state === 'COMPLETED' || run.state === 'FAILED') {
      return { state: 'COMPLETED', run: toRunRecord(run) };
    }
    switch (run.scope) {
      case 'USER_KEY':
        return this.runUserKey(run);
      case 'USER_TYPE_MASTER':
        return this.runMasterKey(run);
      case 'JWT_SIGNING':
        return this.runSigningKey(run);
    }
  }

  /**
   * Marks a run as failed after its retries are exhausted. A failed run is never
   * executed again; the next sweep opens a fresh run for whatever it left behind.
   */
  public async fail(runId: string, errorCode: string): Promise<void> {
    await this.keystore.keyRotationRun.updateMany({
      where: { publicId: runId, state: { in: ['PENDING', 'RUNNING'] } },
      data: { state: 'FAILED', errorCode, finishedAt: this.settings.now() },
    });
  }

  public async get(runId: string): Promise<RotationRunRecord> {
    return toRunRecord(await loadRun(this.keystore, runId));
  }

  public list(options: { limit: number; cursor?: string | undefined }): Promise<RunPage> {
    return listRuns(this.keystore, options);
  }

  /**
   * The periodic check: asks the policy which keys are due, records runs for
   * them, and also picks up versions the vault rotated on its own schedule.
   * Returns the ids of every unfinished run, for the caller to execute, and how
   * many due user keys could not be started (they are retried next time).
   */
  public async sweep(): Promise<{ runIds: string[]; skippedUsers: number }> {
    const now = this.settings.now();

    for (const userType of ['ADMIN', 'MEMBER'] as const) {
      const keyName = this.userKeys.masterKeyFor(userType);
      if ((await findUnfinishedRun(this.keystore, 'USER_TYPE_MASTER', keyName)) !== null) {
        continue;
      }
      const info = await this.kms.getKeyInfo(keyName);
      const newest = info.versions.find((version) => version.version === info.latestVersion);
      const due =
        newest !== undefined &&
        isRotationDue({
          lastRotatedAt: newest.createdAt,
          now,
          intervalDays: this.settings.rotation.userTypeKeyDays,
        });
      if (due) {
        await this.createVaultRun('USER_TYPE_MASTER', keyName, 'SCHEDULED', undefined, null);
      } else if (
        (await this.countUserKeysBelow(keyName, info.latestVersion)) > 0 ||
        info.minDecryptionVersion < info.latestVersion
      ) {
        // The vault rotated by itself (or a run was lost): finish the job without rotating again.
        await this.createVaultRun(
          'USER_TYPE_MASTER',
          keyName,
          'SCHEDULED',
          undefined,
          info.latestVersion,
        );
      }
    }

    const signingKey = this.settings.keys.jwt;
    if ((await findUnfinishedRun(this.keystore, 'JWT_SIGNING', signingKey)) === null) {
      const info = await this.kms.getKeyInfo(signingKey);
      const newest = info.versions.find((version) => version.version === info.latestVersion);
      const due =
        newest !== undefined &&
        isRotationDue({
          lastRotatedAt: newest.createdAt,
          now,
          intervalDays: this.settings.rotation.jwtKeyDays,
        });
      if (due) {
        await this.createVaultRun('JWT_SIGNING', signingKey, 'SCHEDULED', undefined, null);
      } else if (info.minDecryptionVersion < info.latestVersion) {
        await this.createVaultRun(
          'JWT_SIGNING',
          signingKey,
          'SCHEDULED',
          undefined,
          info.latestVersion,
        );
      }
    }

    const candidates = await this.keystore.userKey.findMany({
      where: { state: 'ACTIVE' },
      orderBy: { createdAt: 'asc' },
      take: SWEEP_USER_KEY_LIMIT,
      select: { userId: true, createdAt: true },
    });
    let skippedUsers = 0;

    // Users left with a retired, undestroyed version and no open run (their
    // rotation failed or was superseded) get a fresh run that finishes the job:
    // re-wrap to the active version, then destroy the retired ones.
    const stranded = await this.keystore.userKey.findMany({
      where: { state: 'RETIRED' },
      distinct: ['userId'],
      take: SWEEP_USER_KEY_LIMIT,
      select: { userId: true },
    });
    for (const { userId } of stranded) {
      try {
        await this.resumeUserKeyRotation(userId);
      } catch {
        skippedUsers += 1;
      }
    }

    for (const candidate of candidates) {
      const due = isRotationDue({
        lastRotatedAt: candidate.createdAt,
        now,
        intervalDays: this.settings.rotation.userKeyDays,
      });
      if (!due) {
        continue;
      }
      try {
        await this.beginUserKeyRotation(candidate.userId, 'SCHEDULED', undefined);
      } catch {
        // One user's key that cannot be rotated right now must not stop the others.
        // It stays due and is tried again by the next sweep.
        skippedUsers += 1;
      }
    }

    const unfinished = await this.keystore.keyRotationRun.findMany({
      where: { state: { in: ['PENDING', 'RUNNING'] } },
      orderBy: { id: 'asc' },
      select: { publicId: true },
    });
    return { runIds: unfinished.map((run) => run.publicId), skippedUsers };
  }

  /**
   * Opens a run that re-wraps a user's data keys to the ACTIVE version and then
   * destroys the retired versions, without creating a new version. Used for
   * rotations that failed or were superseded. Does nothing if a run is open.
   */
  private async resumeUserKeyRotation(userId: string): Promise<void> {
    await this.keystore.$transaction(async (tx) => {
      const rows = await tx.$queryRaw<{ id: number; version: number; state: string }[]>`
        SELECT id, version, state::text AS state FROM user_keys
        WHERE user_id = ${userId}::uuid ORDER BY version FOR UPDATE`;
      if ((await findUnfinishedRun(tx, 'USER_KEY', userId)) !== null) {
        return;
      }
      const active = rows.find((row) => row.state === 'ACTIVE');
      const retired = rows.filter((row) => row.state === 'RETIRED');
      if (active === undefined || retired.length === 0) {
        return;
      }
      await tx.keyRotationRun.create({
        data: {
          scope: 'USER_KEY',
          target: userId,
          trigger: 'SCHEDULED',
          fromVersion: Math.min(...retired.map((row) => row.version)),
          toVersion: active.version,
          totalItems: await tx.dataKey.count({
            where: { userId, userKeyVersion: { not: active.version } },
          }),
          requestedBy: null,
          createdAt: this.settings.now(),
        },
      });
    });
  }

  /**
   * Makes a new user-key version active and retires the current one. From this
   * moment new data keys are wrapped under the new version; the retired version
   * keeps unwrapping until {@link run} has re-wrapped everything and destroys it.
   */
  public async beginUserKeyRotation(
    userId: string,
    trigger: RotationTrigger,
    requestedBy: string | undefined,
  ): Promise<RotationRunRecord> {
    const current = await this.keystore.userKey.findFirst({
      where: { userId, state: 'ACTIVE' },
      orderBy: { version: 'desc' },
    });
    if (current === null) {
      // Raises KEY_NOT_FOUND or KEY_DESTROYED with the right meaning.
      await this.userKeys.activeVersion(userId);
      throw new KeyringError('INVALID_STATE', 'This user has no active key.');
    }
    // The vault call happens outside the transaction; the key is discarded if it turns out unneeded.
    const newUserKey = randomKey();
    try {
      const wrappedKey = await this.kms.encrypt(current.masterKeyName, newUserKey, userId);
      const run = await this.keystore.$transaction(async (tx) => {
        // Exclusive lock on the user's key rows: serialises rotations of one user
        // and waits for writers that hold the active version with a shared lock.
        const rows = await tx.$queryRaw<{ id: number; version: number; state: string }[]>`
          SELECT id, version, state::text AS state FROM user_keys
          WHERE user_id = ${userId}::uuid ORDER BY version FOR UPDATE`;
        const unfinished = await findUnfinishedRun(tx, 'USER_KEY', userId);
        if (unfinished !== null) {
          return unfinished;
        }
        const active = rows.find((row) => row.state === 'ACTIVE');
        if (active === undefined) {
          throw new KeyringError('KEY_DESTROYED', 'The keys of this user were destroyed.');
        }
        const now = this.settings.now();
        const newVersion = Math.max(...rows.map((row) => row.version)) + 1;
        await tx.userKey.update({
          where: { id: active.id },
          data: { state: 'RETIRED', retiredAt: now },
        });
        await tx.userKey.create({
          data: {
            userId,
            version: newVersion,
            wrappedKey,
            masterKeyName: current.masterKeyName,
            masterKeyVersion: ciphertextKeyVersion(wrappedKey),
            state: 'ACTIVE',
            createdAt: now,
          },
        });
        const totalItems = await tx.dataKey.count({
          where: { userId, userKeyVersion: { not: newVersion } },
        });
        return tx.keyRotationRun.create({
          data: {
            scope: 'USER_KEY',
            target: userId,
            trigger,
            fromVersion: active.version,
            toVersion: newVersion,
            totalItems,
            requestedBy: requestedBy ?? null,
            createdAt: now,
          },
        });
      });
      return toRunRecord(run);
    } finally {
      zeroize(newUserKey);
    }
  }

  private async createVaultRun(
    scope: 'USER_TYPE_MASTER' | 'JWT_SIGNING',
    target: string,
    trigger: RotationTrigger,
    requestedBy: string | undefined,
    /** Set when the vault already has the new version and only the follow-up is needed. */
    toVersion: number | null,
  ): Promise<RotationRunRecord> {
    const run = await this.keystore.$transaction(async (tx) => {
      await lockTarget(tx, scope, target);
      const unfinished = await findUnfinishedRun(tx, scope, target);
      if (unfinished !== null) {
        return unfinished;
      }
      return tx.keyRotationRun.create({
        data: {
          scope,
          target,
          trigger,
          toVersion,
          requestedBy: requestedBy ?? null,
          createdAt: this.settings.now(),
        },
      });
    });
    return toRunRecord(run);
  }

  // ── USER_KEY ────────────────────────────────────────────────────────────

  private async runUserKey(initial: KeyRotationRun): Promise<RotationProgress> {
    const userId = initial.target;
    const toVersion = initial.toVersion;
    if (toVersion === null) {
      throw new KeyringError('INVALID_STATE', 'A user-key run must name its new version.');
    }
    await this.markRunning(initial);

    for (;;) {
      // Only ever move keys to the version that is active right now. A newer
      // rotation supersedes this run; that run re-wraps from wherever keys are.
      const active = await this.keystore.userKey.findFirst({
        where: { userId, state: 'ACTIVE' },
        select: { version: true },
      });
      if (active === null) {
        return this.finishWithoutWork(initial, 'USER_KEYS_DESTROYED');
      }
      if (active.version !== toVersion) {
        return this.finishWithoutWork(initial, 'SUPERSEDED');
      }
      let rewrapped: number;
      try {
        rewrapped = await this.rewrapDataKeyBatch(initial.id, userId, toVersion);
      } catch (error) {
        if (error instanceof KeyringError && error.code === 'KEY_DESTROYED') {
          // The user was deleted while the run was in flight: nothing left to protect.
          return this.finishWithoutWork(initial, 'USER_KEYS_DESTROYED');
        }
        throw error;
      }
      if (rewrapped > 0) {
        continue;
      }
      const destroyed = await this.keystore.$transaction(async (tx) => {
        // Exclusive lock: no writer can be adding a data key under a retired
        // version while we check that none is left and destroy those versions.
        const rows = await tx.$queryRaw<{ id: number; version: number; state: string }[]>`
          SELECT id, version, state::text AS state FROM user_keys
          WHERE user_id = ${userId}::uuid ORDER BY version FOR UPDATE`;
        // Re-checked under the lock: destroying anything is only safe while the
        // version every data key now sits under is still the active one.
        if (rows.find((row) => row.version === toVersion)?.state !== 'ACTIVE') {
          return 'SUPERSEDED' as const;
        }
        const remaining = await tx.dataKey.count({
          where: { userId, userKeyVersion: { not: toVersion } },
        });
        if (remaining > 0) {
          return null;
        }
        const retired = rows.filter((row) => row.state === 'RETIRED' && row.version !== toVersion);
        const now = this.settings.now();
        // "Destroy" is an UPDATE: the runtime role cannot delete user keys.
        await tx.userKey.updateMany({
          where: { id: { in: retired.map((row) => row.id) } },
          data: { wrappedKey: null, state: 'DESTROYED', destroyedAt: now },
        });
        await tx.keyRotationRun.update({
          where: { id: initial.id },
          data: { state: 'COMPLETED', finishedAt: now, errorCode: null },
        });
        return retired.map((row) => row.version);
      });
      if (destroyed === 'SUPERSEDED') {
        return this.finishWithoutWork(initial, 'SUPERSEDED');
      }
      if (destroyed !== null) {
        for (const version of destroyed) {
          this.userKeys.evictVersion(userId, version);
        }
        return { state: 'COMPLETED', run: await this.get(initial.publicId) };
      }
    }
  }

  /** Re-wraps up to one batch of a user's data keys under the new version. Returns how many. */
  private async rewrapDataKeyBatch(
    runDbId: number,
    userId: string,
    toVersion: number,
  ): Promise<number> {
    const rows = await this.keystore.dataKey.findMany({
      where: { userId, userKeyVersion: { not: toVersion } },
      orderBy: { id: 'asc' },
      take: this.settings.rotation.rewrapBatchSize,
    });
    if (rows.length === 0) {
      return 0;
    }
    const wrapKeys = new Map<number, Buffer>();
    try {
      const newWrapKey = await this.userKeys.wrapKey(userId, toVersion);
      wrapKeys.set(toVersion, newWrapKey);
      const updates: { id: number; fromVersion: number; wrappedKey: Buffer<ArrayBuffer> }[] = [];
      for (const row of rows) {
        let oldWrapKey = wrapKeys.get(row.userKeyVersion);
        if (oldWrapKey === undefined) {
          oldWrapKey = await this.userKeys.wrapKey(userId, row.userKeyVersion);
          wrapKeys.set(row.userKeyVersion, oldWrapKey);
        }
        const binding = { scope: row.scope, userId, refId: row.refId };
        const dataKey = unwrapDataKey(oldWrapKey, row.wrappedKey, {
          ...binding,
          userKeyVersion: row.userKeyVersion,
        });
        try {
          updates.push({
            id: row.id,
            fromVersion: row.userKeyVersion,
            wrappedKey: wrapDataKey(newWrapKey, dataKey, { ...binding, userKeyVersion: toVersion }),
          });
        } finally {
          zeroize(dataKey);
        }
      }
      return await this.keystore.$transaction(async (tx) => {
        let changed = 0;
        for (const update of updates) {
          // Conditional on the old version: a row someone else already moved is left alone.
          const result = await tx.dataKey.updateMany({
            where: { id: update.id, userKeyVersion: update.fromVersion },
            data: { wrappedKey: update.wrappedKey, userKeyVersion: toVersion },
          });
          changed += result.count;
        }
        await tx.keyRotationRun.update({
          where: { id: runDbId },
          data: { processedItems: { increment: changed } },
        });
        // Rows that vanished (document deleted meanwhile) still count as handled.
        return updates.length;
      });
    } finally {
      zeroize(...wrapKeys.values());
    }
  }

  // ── USER_TYPE_MASTER ────────────────────────────────────────────────────

  private async runMasterKey(initial: KeyRotationRun): Promise<RotationProgress> {
    const keyName = initial.target;
    const run = await this.ensureVaultVersion(initial, async (toVersion) =>
      this.countUserKeysBelow(keyName, toVersion),
    );
    const toVersion = run.toVersion;
    if (toVersion === null || run.startedAt === null) {
      throw new KeyringError('INVALID_STATE', 'Master-key run has no target version.');
    }

    for (;;) {
      const rows = await this.keystore.userKey.findMany({
        where: {
          masterKeyName: keyName,
          masterKeyVersion: { lt: toVersion },
          state: { not: 'DESTROYED' },
          wrappedKey: { not: null },
        },
        orderBy: { id: 'asc' },
        take: this.settings.rotation.rewrapBatchSize,
        select: { id: true, userId: true, wrappedKey: true },
      });
      if (rows.length === 0) {
        break;
      }
      // Re-wrapped inside the vault: the user keys are never exposed to this process.
      const rewrapped = await this.kms.rewrap(
        keyName,
        rows.map((row) => ({ ciphertext: row.wrappedKey ?? '', context: row.userId })),
      );
      await this.keystore.$transaction(async (tx) => {
        let changed = 0;
        for (const [index, row] of rows.entries()) {
          const wrappedKey = rewrapped[index];
          if (wrappedKey === undefined || ciphertextKeyVersion(wrappedKey) < toVersion) {
            throw new KeyringError(
              'INVALID_STATE',
              'The vault did not re-wrap under the new version.',
            );
          }
          // Conditional on the old ciphertext: a row changed meanwhile is picked up next round.
          const result = await tx.userKey.updateMany({
            where: { id: row.id, wrappedKey: row.wrappedKey },
            data: { wrappedKey, masterKeyVersion: ciphertextKeyVersion(wrappedKey) },
          });
          changed += result.count;
        }
        await tx.keyRotationRun.update({
          where: { id: run.id },
          data: { processedItems: { increment: changed } },
        });
      });
    }

    // A user key wrapped under the old version a moment before the rotation may
    // still be on its way into the database. Give it time to land (and be
    // re-wrapped above) before the old version stops decrypting.
    const settleAt = run.startedAt.getTime() + this.settings.masterKeySettleSeconds * 1000;
    const now = this.settings.now().getTime();
    if (now < settleAt) {
      return { state: 'WAITING', run: await this.get(run.publicId), retryAfterMs: settleAt - now };
    }
    if ((await this.countUserKeysBelow(keyName, toVersion)) > 0) {
      // A late arrival landed during the settle time: re-wrap it, then check again.
      return this.runMasterKey(run);
    }
    await this.retireOlderVersions(keyName, toVersion);
    return this.complete(run);
  }

  // ── JWT_SIGNING ─────────────────────────────────────────────────────────

  private async runSigningKey(initial: KeyRotationRun): Promise<RotationProgress> {
    const run = await this.ensureVaultVersion(initial, () => Promise.resolve(0));
    if (run.toVersion === null || run.startedAt === null) {
      throw new KeyringError('INVALID_STATE', 'Signing-key run has no target version.');
    }
    // Tokens signed by the old version must be able to verify until they expire.
    const retireAt =
      run.startedAt.getTime() +
      (this.settings.accessTokenTtlSeconds + this.settings.signingKeyRetireMarginSeconds) * 1000;
    const now = this.settings.now().getTime();
    if (now < retireAt) {
      return { state: 'WAITING', run: await this.get(run.publicId), retryAfterMs: retireAt - now };
    }
    await this.retireOlderVersions(run.target, run.toVersion);
    return this.complete(run);
  }

  // ── shared steps ────────────────────────────────────────────────────────

  /**
   * Makes sure the vault has the run's new key version and records it. Written
   * so a crash at any point resumes correctly: the version before the rotation
   * is stored first, so a second attempt can tell that the rotation already
   * happened and does not rotate twice.
   */
  private async ensureVaultVersion(
    initial: KeyRotationRun,
    countItems: (toVersion: number) => Promise<number>,
  ): Promise<KeyRotationRun> {
    let run = initial;
    if (run.toVersion !== null && run.startedAt !== null && run.state === 'RUNNING') {
      return run;
    }
    const now = this.settings.now();
    let toVersion = run.toVersion;
    if (toVersion === null) {
      const before = await this.kms.getKeyInfo(run.target);
      if (run.fromVersion === null) {
        run = await this.keystore.keyRotationRun.update({
          where: { id: run.id },
          data: {
            fromVersion: before.latestVersion,
            state: 'RUNNING',
            errorCode: null,
            finishedAt: null,
          },
        });
      }
      const fromVersion = run.fromVersion ?? before.latestVersion;
      toVersion =
        before.latestVersion > fromVersion
          ? before.latestVersion
          : (await this.kms.rotateKey(run.target)).latestVersion;
    }
    return this.keystore.keyRotationRun.update({
      where: { id: run.id },
      data: {
        toVersion,
        totalItems: await countItems(toVersion),
        state: 'RUNNING',
        errorCode: null,
        finishedAt: null,
        // The waiting periods count from the moment the new version is known to exist.
        startedAt: run.startedAt ?? now,
      },
    });
  }

  private async retireOlderVersions(keyName: string, toVersion: number): Promise<void> {
    const info = await this.kms.getKeyInfo(keyName);
    if (info.minDecryptionVersion < toVersion) {
      await this.kms.setMinDecryptionVersion(keyName, toVersion);
    }
  }

  private countUserKeysBelow(keyName: string, version: number): Promise<number> {
    return this.keystore.userKey.count({
      where: {
        masterKeyName: keyName,
        masterKeyVersion: { lt: version },
        state: { not: 'DESTROYED' },
      },
    });
  }

  private async markRunning(run: KeyRotationRun): Promise<void> {
    await this.keystore.keyRotationRun.update({
      where: { id: run.id },
      data: {
        state: 'RUNNING',
        errorCode: null,
        finishedAt: null,
        startedAt: run.startedAt ?? this.settings.now(),
      },
    });
  }

  private async complete(run: KeyRotationRun): Promise<RotationProgress> {
    const completed = await this.keystore.keyRotationRun.update({
      where: { id: run.id },
      data: { state: 'COMPLETED', finishedAt: this.settings.now(), errorCode: null },
    });
    return { state: 'COMPLETED', run: toRunRecord(completed) };
  }

  private async finishWithoutWork(
    run: KeyRotationRun,
    errorCode: string,
  ): Promise<RotationProgress> {
    const finished = await this.keystore.keyRotationRun.update({
      where: { id: run.id },
      data: { state: 'FAILED', finishedAt: this.settings.now(), errorCode },
    });
    return { state: 'COMPLETED', run: toRunRecord(finished) };
  }
}
