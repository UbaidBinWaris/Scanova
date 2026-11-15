import { randomUUID } from 'node:crypto';
import type { IncomingMessage } from 'node:http';
import { Readable, pipeline } from 'node:stream';
import { Inject, Injectable } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import type { Document as DocumentDto, UploadHeaders } from '@scanova/contracts/documents';
import { JOB_NAMES } from '@scanova/contracts/jobs';
import { deriveObjectKey } from '@scanova/crypto/hierarchy';
import { zeroize } from '@scanova/crypto/zeroize';
import type { DatabaseClient } from '@scanova/database/client';
import type { JobProducer } from '@scanova/jobs/queue';
import type { Keyring } from '@scanova/keyring/keyring';
import type { EncryptedObjectStore } from '@scanova/storage/encrypted';
import { objectKeyFor } from '@scanova/storage/object-keys';
import { AppError } from '../common/app-error.js';
import type { AuthContext } from '../common/auth-context.js';
import { AppLogger } from '@scanova/config/logger';
import { API_CONFIG, DATABASE, ENCRYPTED_STORE, JOBS, KEYRING } from '../platform/tokens.js';
import { DocumentsService } from './documents.service.js';
import { UploadSlots } from './upload-slots.js';
import { UploadVerifier } from './upload-verifier.js';

const PDF_CONTENT_TYPE = /^application\/pdf\s*(;.*)?$/iu;

/** Concurrent uploads allowed per user and in total (see UploadSlots). */
const MAX_UPLOADS_PER_USER = 2;
const MAX_UPLOADS_TOTAL = 8;
/** An upload whose body sends nothing for this long is aborted, freeing its slot. */
const UPLOAD_IDLE_TIMEOUT_MS = 60_000;

/**
 * `PUT /documents/:id/file` (§7.3): body → size/signature/SHA-256 check →
 * SCNV1 encryption → multipart upload. The body is never buffered whole;
 * memory use is one chunk plus one upload part whatever the file size.
 */
@Injectable()
export class UploadService {
  private readonly slots = new UploadSlots(MAX_UPLOADS_PER_USER, MAX_UPLOADS_TOTAL);

  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(ENCRYPTED_STORE) private readonly objects: EncryptedObjectStore,
    @Inject(JOBS) private readonly jobs: JobProducer,
    @Inject(API_CONFIG) private readonly config: ApiConfig,
    private readonly documents: DocumentsService,
    private readonly logger: AppLogger,
  ) {}

  public async upload(
    auth: AuthContext,
    documentId: string,
    headers: UploadHeaders,
    contentType: string | undefined,
    request: IncomingMessage,
  ): Promise<DocumentDto> {
    if (contentType === undefined || !PDF_CONTENT_TYPE.test(contentType)) {
      throw new AppError('UNSUPPORTED_MEDIA_TYPE');
    }
    const declaredLength = headers['content-length'];
    if (declaredLength > this.config.http.maxUploadBytes) {
      throw new AppError('FILE_TOO_LARGE');
    }
    const release = this.slots.tryAcquire(auth.userId);
    if (release === null) {
      drain(request);
      throw new AppError('RATE_LIMITED', { headers: { 'Retry-After': '10' } });
    }
    // A body that stops arriving is cut off instead of holding its slot for the request timeout.
    const socket = request.socket;
    const onIdle = (): void => {
      // Same code as a client that hung up, so toUploadError reports it as an interrupted upload.
      request.destroy(Object.assign(new Error('Upload idle timeout.'), { code: 'ABORT_ERR' }));
    };
    socket.setTimeout(UPLOAD_IDLE_TIMEOUT_MS, onIdle);
    try {
      return await this.receive(auth, documentId, headers, declaredLength, request);
    } finally {
      socket.setTimeout(0);
      socket.off('timeout', onIdle);
      release();
    }
  }

  private async receive(
    auth: AuthContext,
    documentId: string,
    headers: UploadHeaders,
    declaredLength: number,
    request: IncomingMessage,
  ): Promise<DocumentDto> {
    const document = await this.documents.owned(auth, documentId);
    const previous = document.files.find((file) => file.variant === 'ORIGINAL');
    if (
      previous !== undefined &&
      Buffer.from(previous.sha256).toString('hex') === headers['x-content-sha256']
    ) {
      // The same file again (a retry after a lost answer): nothing to do.
      return this.documents.toDto(auth, document);
    }
    await this.assertQuota(auth, declaredLength - Number(previous?.sizeBytes ?? 0n));

    // A fresh object id per upload: each stored object has its own stream key, used once.
    const objectId = randomUUID();
    const objectKey = objectKeyFor({ userId: auth.userPublicId, documentId, objectId });
    const verifier = new UploadVerifier({
      declaredLength,
      maxBytes: this.config.http.maxUploadBytes,
      sha256Hex: headers['x-content-sha256'],
    });
    const streamKey = await this.keyring.useDocumentKey(auth.userPublicId, documentId, (key) =>
      deriveObjectKey(key, objectId),
    );
    try {
      // Read through an iterator that leaves the socket alone when the upload
      // fails, so the error answer can still be delivered to the client.
      const body = Readable.from(request.iterator({ destroyOnReturn: false }), {
        objectMode: false,
      });
      pipeline(body, verifier, () => undefined);
      await this.objects.put({ objectKey, objectId, streamKey }, verifier);
    } catch (error) {
      drain(request);
      throw toUploadError(error);
    } finally {
      zeroize(streamKey);
    }

    let replaced: string[];
    try {
      replaced = await this.commit(auth, document.id, {
        objectId,
        objectKey,
        sizeBytes: verifier.size,
        sha256Hex: headers['x-content-sha256'],
      });
    } catch (error) {
      await this.objects.delete(objectKey).catch(() => undefined);
      throw error;
    }

    // The new object is committed; what it replaced is now garbage.
    for (const staleKey of replaced) {
      await this.objects.delete(staleKey).catch((error: unknown) => {
        this.logger.failure('upload.stale_object_not_deleted', error, { documentId });
      });
    }
    if (this.config.features.serverOcr) {
      await this.jobs.enqueue(JOB_NAMES.documentOcr, { documentId }).catch((error: unknown) => {
        // The document stays PENDING; the worker re-queues stale pending documents.
        this.logger.failure('upload.ocr_not_queued', error, { documentId });
      });
    }
    return this.documents.get(auth, documentId);
  }

  private async assertQuota(auth: AuthContext, deltaBytes: number): Promise<void> {
    const user = await this.database.user.findUniqueOrThrow({
      where: { id: auth.userId },
      select: { storageUsedBytes: true, storageQuotaBytes: true },
    });
    if (
      deltaBytes > 0 &&
      user.storageQuotaBytes !== null &&
      user.storageUsedBytes + BigInt(deltaBytes) > user.storageQuotaBytes
    ) {
      throw new AppError('QUOTA_EXCEEDED');
    }
  }

  /**
   * Makes the uploaded object the document's original, in one transaction with
   * the quota accounting. Returns the bucket keys of the objects it replaced.
   */
  private commit(
    auth: AuthContext,
    documentDbId: number,
    file: { objectId: string; objectKey: string; sizeBytes: number; sha256Hex: string },
  ): Promise<string[]> {
    return this.database.$transaction(async (tx) => {
      // Every writer of a document's files (uploads, the OCR commit, purge) locks the
      // document row first, so the file list and the sizes below cannot be stale.
      const locked = await tx.$queryRaw<{ id: number }[]>`
        SELECT id FROM documents
        WHERE id = ${documentDbId} AND user_id = ${auth.userId} AND deleted_at IS NULL
        FOR UPDATE`;
      if (locked.length === 0) {
        // Deleted while the upload was in flight.
        throw new AppError('NOT_FOUND');
      }
      const files = await tx.fileObject.findMany({ where: { documentId: documentDbId } });
      // All stored variants count towards the quota, and all of them are replaced.
      const previousBytes = files.reduce((sum, entry) => sum + entry.sizeBytes, 0n);
      const delta = BigInt(file.sizeBytes) - previousBytes;
      // The quota is enforced by the UPDATE itself, so two parallel uploads cannot both slip under it.
      const accounted = await tx.$executeRaw`
        UPDATE users SET storage_used_bytes = storage_used_bytes + ${delta}
        WHERE id = ${auth.userId}
          AND (${delta} <= 0 OR storage_quota_bytes IS NULL OR storage_used_bytes + ${delta} <= storage_quota_bytes)`;
      if (accounted !== 1) {
        throw new AppError('QUOTA_EXCEEDED');
      }
      // A new original invalidates the searchable PDF derived from the old one.
      await tx.fileObject.deleteMany({ where: { documentId: documentDbId } });
      await tx.fileObject.create({
        data: {
          publicId: file.objectId,
          documentId: documentDbId,
          variant: 'ORIGINAL',
          objectKey: file.objectKey,
          sizeBytes: BigInt(file.sizeBytes),
          sha256: Buffer.from(file.sha256Hex, 'hex'),
        },
      });
      await tx.document.update({
        where: { id: documentDbId },
        data: { status: 'READY', ocrStatus: this.config.features.serverOcr ? 'PENDING' : 'NONE' },
      });
      return files.map((entry) => entry.objectKey);
    });
  }
}

/** Reads and discards what is left of the request body, so the connection can answer and be reused. */
function drain(request: IncomingMessage): void {
  if (!request.destroyed && !request.readableEnded) {
    request.resume();
  }
}

function toUploadError(error: unknown): unknown {
  const code = (error as { code?: unknown }).code;
  if (code === 'ERR_STREAM_PREMATURE_CLOSE' || code === 'ECONNRESET' || code === 'ABORT_ERR') {
    // The client went away mid-upload. Nobody will read this answer; it only keeps the log honest.
    return new AppError('VALIDATION_FAILED', {
      message: 'The upload was interrupted.',
      details: [{ field: 'body', message: 'The upload was interrupted.' }],
    });
  }
  return error;
}
