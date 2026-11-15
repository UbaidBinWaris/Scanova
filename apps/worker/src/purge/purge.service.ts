import { Inject, Injectable } from '@nestjs/common';
import { AppLogger } from '@scanova/config/logger';
import type { WorkerConfig } from '@scanova/config/sections';
import { JOB_NAMES } from '@scanova/contracts/jobs';
import type { DatabaseClient } from '@scanova/database/client';
import type { JobProducer } from '@scanova/jobs/queue';
import type { Keyring } from '@scanova/keyring/keyring';
import type { ObjectStore } from '@scanova/storage/object-store';
import { userObjectPrefix } from '@scanova/storage/object-keys';
import {
  CLOCK,
  DATABASE,
  JOBS,
  KEYRING,
  OBJECT_STORE,
  WORKER_CONFIG,
  type Clock,
} from '../platform/tokens.js';

const DAY_MS = 86_400_000;
const BATCH = 100;
/** A document still waiting for OCR after this long has lost its job; it is queued again. */
const STALE_OCR_MS = 15 * 60_000;
/** Revoked or expired sessions are kept this long for the reuse detection, then removed. */
const SESSION_RETENTION_MS = 7 * DAY_MS;
/** Keys younger than this are never garbage: their data may still be on its way. */
const KEY_GC_GRACE_MS = DAY_MS;

/**
 * Permanent deletion. The order is always the same (§5.2): destroy the key,
 * then the objects, then the rows — so at no point does data exist that could
 * still be decrypted but is supposed to be gone.
 */
@Injectable()
export class PurgeService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(OBJECT_STORE) private readonly objects: ObjectStore,
    @Inject(JOBS) private readonly jobs: JobProducer,
    @Inject(WORKER_CONFIG) private readonly config: WorkerConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: AppLogger,
  ) {}

  /** Purges documents that have been in the trash longer than `TRASH_RETENTION_DAYS`. */
  public async purgeTrash(): Promise<number> {
    const cutoff = new Date(
      this.clock().getTime() - this.config.limits.trashRetentionDays * DAY_MS,
    );
    let purged = 0;
    for (;;) {
      const documents = await this.database.document.findMany({
        where: { deletedAt: { lt: cutoff } },
        orderBy: { id: 'asc' },
        take: BATCH,
        include: { files: true, user: { select: { id: true, publicId: true } } },
      });
      if (documents.length === 0) {
        break;
      }
      for (const document of documents) {
        // 1. The DOCUMENT key: from here on the document is unrecoverable.
        await this.keyring.destroyDocumentKeys(document.user.publicId, [document.publicId]);
        // 2. Its ciphertext objects.
        await this.objects.deleteMany(document.files.map((file) => file.objectKey));
        // 3. Its rows, and the space it used.
        // Every stored variant was counted, so every variant is given back.
        const storedBytes = document.files.reduce((sum, file) => sum + file.sizeBytes, 0n);
        await this.database.$transaction([
          this.database.document.delete({ where: { id: document.id } }),
          this.database.$executeRaw`
            UPDATE users SET storage_used_bytes = GREATEST(storage_used_bytes - ${storedBytes}, 0)
            WHERE id = ${document.user.id}`,
        ]);
        purged += 1;
      }
    }
    this.logger.info('purge.trash', { purged });
    return purged;
  }

  /** Removes everything of a crypto-shredded user: ciphertext objects, then the rows. */
  public async purgeUser(userId: string): Promise<boolean> {
    const user = await this.database.user.findUnique({
      where: { publicId: userId },
      select: { id: true, deletedAt: true },
    });
    if (user === null) {
      return false;
    }
    if (user.deletedAt === null) {
      // Only the API's delete marks a user for purging. Never purge a live account.
      this.logger.event('warn', 'purge.user_not_deleted', { userId });
      return false;
    }
    // Idempotent, and it closes the gap if the API stopped between its two steps.
    await this.keyring.destroyUser(userId);
    let batch: string[] = [];
    let objects = 0;
    for await (const key of this.objects.list(userObjectPrefix(userId))) {
      batch.push(key);
      if (batch.length >= 500) {
        await this.objects.deleteMany(batch);
        objects += batch.length;
        batch = [];
      }
    }
    await this.objects.deleteMany(batch);
    objects += batch.length;
    // Sessions, documents, texts, file rows and search tokens go with the user row.
    await this.database.user.delete({ where: { id: user.id } });
    this.logger.info('purge.user', { userId, objects });
    return true;
  }

  /** Housekeeping that rides along with the daily purge. */
  public async housekeeping(): Promise<{ sessions: number; requeued: number }> {
    const now = this.clock().getTime();
    const old = new Date(now - SESSION_RETENTION_MS);
    const sessions = await this.database.session.deleteMany({
      where: { OR: [{ revokedAt: { lt: old } }, { refreshExpiresAt: { lt: old } }] },
    });
    const requeued = await this.requeueStaleOcr();
    return { sessions: sessions.count, requeued };
  }

  /** Documents whose OCR job was lost (queue unavailable at upload time) get a new one. */
  public async requeueStaleOcr(): Promise<number> {
    const stale = await this.database.document.findMany({
      where: {
        status: 'READY',
        ocrStatus: 'PENDING',
        deletedAt: null,
        updatedAt: { lt: new Date(this.clock().getTime() - STALE_OCR_MS) },
      },
      select: { publicId: true },
      take: 1000,
    });
    let requeued = 0;
    for (const document of stale) {
      if (
        (await this.jobs.enqueue(JOB_NAMES.documentOcr, { documentId: document.publicId })) !== null
      ) {
        requeued += 1;
      }
    }
    return requeued;
  }

  /**
   * Garbage-collects keys that protect nothing: a DOCUMENT key whose document
   * was never created, or the keys of a user whose row never came to exist.
   * Keys are created before their data, so such orphans are expected and
   * harmless; this only keeps the key database tidy.
   */
  public async collectOrphanKeys(): Promise<{ documentKeys: number; users: number }> {
    const cutoff = new Date(this.clock().getTime() - KEY_GC_GRACE_MS);
    let documentKeys = 0;
    let cursor: number | undefined;
    for (;;) {
      const refs = await this.keyring.listUnclaimedDocumentKeys({
        claimedBefore: cutoff,
        afterCursor: cursor,
        limit: 500,
      });
      if (refs.length === 0) {
        break;
      }
      cursor = refs.at(-1)?.cursor;
      const existing = await this.database.document.findMany({
        where: { publicId: { in: refs.map((ref) => ref.documentId) } },
        select: { publicId: true, user: { select: { publicId: true } } },
      });
      const owned = new Set(
        existing.map((document) => `${document.user.publicId}/${document.publicId}`),
      );
      const orphans = refs.filter((ref) => !owned.has(`${ref.userId}/${ref.documentId}`));
      documentKeys += await this.keyring.deleteUnclaimedDocumentKeys(orphans, cutoff);
    }

    let users = 0;
    let afterUserId: string | undefined;
    for (;;) {
      const owners = await this.keyring.listKeyOwners({
        createdBefore: cutoff,
        afterUserId,
        limit: 500,
      });
      if (owners.length === 0) {
        break;
      }
      afterUserId = owners.at(-1);
      const existing = await this.database.user.findMany({
        where: { publicId: { in: owners } },
        select: { publicId: true },
      });
      const known = new Set(existing.map((user) => user.publicId));
      for (const owner of owners) {
        if (!known.has(owner)) {
          await this.keyring.destroyUser(owner);
          users += 1;
        }
      }
    }
    this.logger.info('purge.orphan_keys', { documentKeys, users });
    return { documentKeys, users };
  }
}
