import { randomUUID } from 'node:crypto';
import type { IncomingMessage } from 'node:http';
import { Readable, pipeline } from 'node:stream';
import { Inject, Injectable } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import type {
  Document as DocumentDto,
  DocumentPage as DocumentPageDto,
  UploadHeaders,
} from '@scanova/contracts/documents';
import { JOB_NAMES } from '@scanova/contracts/jobs';
import { deriveObjectKey } from '@scanova/crypto/hierarchy';
import { zeroize } from '@scanova/crypto/zeroize';
import type { DatabaseClient, DatabaseTransaction } from '@scanova/database/client';
import type { JobProducer } from '@scanova/jobs/queue';
import type { Keyring } from '@scanova/keyring/keyring';
import type { EncryptedObjectStore } from '@scanova/storage/encrypted';
import { objectKeyFor } from '@scanova/storage/object-keys';
import { AppError } from '../common/app-error.js';
import type { AuthContext } from '../common/auth-context.js';
import { AppLogger } from '@scanova/config/logger';
import { API_CONFIG, DATABASE, ENCRYPTED_STORE, JOBS, KEYRING } from '../platform/tokens.js';
import { DocumentsService } from './documents.service.js';
import { PagesService, lockDocument } from './pages.service.js';
import { UploadSlots } from './upload-slots.js';
import {
  JPEG_SIGNATURE,
  PDF_SIGNATURE,
  UploadVerifier,
  type FileSignature,
} from './upload-verifier.js';

const PDF_CONTENT_TYPE = /^application\/pdf\s*(;.*)?$/iu;
const JPEG_CONTENT_TYPE = /^image\/jpeg\s*(;.*)?$/iu;

/** Concurrent uploads allowed per user and in total (see UploadSlots). PDFs and originals share them. */
const MAX_UPLOADS_PER_USER = 2;
const MAX_UPLOADS_TOTAL = 8;
/** An upload whose body sends nothing for this long is aborted, freeing its slot. */
const UPLOAD_IDLE_TIMEOUT_MS = 60_000;

interface StoredObject {
  readonly objectId: string;
  readonly objectKey: string;
  readonly sizeBytes: number;
  readonly sha256Hex: string;
}

/**
 * Streamed uploads (§7.3): `PUT /documents/:id/file` (the PDF) and
 * `PUT /documents/:id/pages/:pageId/original` (a page's original photo).
 * Body → size/signature/SHA-256 check → SCNV1 encryption → multipart upload.
 * The body is never buffered whole; memory use is one chunk plus one upload
 * part whatever the file size. Both kinds count towards the owner's storage
 * quota, accounted in the same transaction that makes the new object current.
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
    private readonly pages: PagesService,
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
      throw new AppError('UNSUPPORTED_MEDIA_TYPE', { message: PDF_SIGNATURE.message });
    }
    return this.withSlot(auth, headers, request, () =>
      this.receivePdf(auth, documentId, headers, request),
    );
  }

  /**
   * A page's original photo (JPEG). The page must already be in the page list
   * (`PUT …/pages`); otherwise `NOT_FOUND`. Uploading the same bytes again is a
   * no-op; different bytes replace the stored original.
   */
  public async uploadPageOriginal(
    auth: AuthContext,
    documentId: string,
    pageId: string,
    headers: UploadHeaders,
    contentType: string | undefined,
    request: IncomingMessage,
  ): Promise<DocumentPageDto> {
    if (contentType === undefined || !JPEG_CONTENT_TYPE.test(contentType)) {
      throw new AppError('UNSUPPORTED_MEDIA_TYPE', { message: JPEG_SIGNATURE.message });
    }
    return this.withSlot(auth, headers, request, () =>
      this.receiveOriginal(auth, documentId, pageId, headers, request),
    );
  }

  /** Size limit, upload slot and idle timeout around one streamed upload. */
  private async withSlot<Result>(
    auth: AuthContext,
    headers: UploadHeaders,
    request: IncomingMessage,
    receive: () => Promise<Result>,
  ): Promise<Result> {
    if (headers['content-length'] > this.config.http.maxUploadBytes) {
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
      return await receive();
    } finally {
      socket.setTimeout(0);
      socket.off('timeout', onIdle);
      release();
    }
  }

  private async receivePdf(
    auth: AuthContext,
    documentId: string,
    headers: UploadHeaders,
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
    await this.assertQuota(auth, headers['content-length'] - Number(previous?.sizeBytes ?? 0n));

    const stored = await this.receiveObject(auth, documentId, headers, request, PDF_SIGNATURE);
    const replaced = await this.commitOrDiscard(stored, () =>
      this.commitPdf(auth, document.id, stored),
    );
    await this.deleteStale(replaced, documentId);
    if (this.config.features.serverOcr) {
      await this.jobs.enqueue(JOB_NAMES.documentOcr, { documentId }).catch((error: unknown) => {
        // The document stays PENDING; the worker re-queues stale pending documents.
        this.logger.failure('upload.ocr_not_queued', error, { documentId });
      });
    }
    return this.documents.get(auth, documentId);
  }

  private async receiveOriginal(
    auth: AuthContext,
    documentId: string,
    pageId: string,
    headers: UploadHeaders,
    request: IncomingMessage,
  ): Promise<DocumentPageDto> {
    const { document, page } = await this.pages.owned(auth, documentId, pageId);
    if (
      page.originalSha256 !== null &&
      Buffer.from(page.originalSha256).toString('hex') === headers['x-content-sha256']
    ) {
      // The same original again (a retry after a lost answer): nothing to do.
      return this.pages.get(auth, documentId, pageId);
    }
    await this.assertQuota(auth, headers['content-length'] - Number(page.originalSizeBytes ?? 0n));

    const stored = await this.receiveObject(auth, documentId, headers, request, JPEG_SIGNATURE);
    const replaced = await this.commitOrDiscard(stored, () =>
      this.commitOriginal(auth, document.id, pageId, stored),
    );
    await this.deleteStale(replaced, documentId);
    return this.pages.get(auth, documentId, pageId);
  }

  /** Streams the request body through the verifier into a new encrypted object. */
  private async receiveObject(
    auth: AuthContext,
    documentId: string,
    headers: UploadHeaders,
    request: IncomingMessage,
    signature: FileSignature,
  ): Promise<StoredObject> {
    // A fresh object id per upload: each stored object has its own stream key, used once.
    const objectId = randomUUID();
    const objectKey = objectKeyFor({ userId: auth.userPublicId, documentId, objectId });
    const verifier = new UploadVerifier({
      declaredLength: headers['content-length'],
      maxBytes: this.config.http.maxUploadBytes,
      sha256Hex: headers['x-content-sha256'],
      signature,
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
    return {
      objectId,
      objectKey,
      sizeBytes: verifier.size,
      sha256Hex: headers['x-content-sha256'],
    };
  }

  /** Runs the commit; if it fails, the object just stored is removed again. */
  private async commitOrDiscard(
    stored: StoredObject,
    commit: () => Promise<string[]>,
  ): Promise<string[]> {
    try {
      return await commit();
    } catch (error) {
      await this.objects.delete(stored.objectKey).catch(() => undefined);
      throw error;
    }
  }

  /** The new object is committed; what it replaced is now garbage. */
  private async deleteStale(objectKeys: readonly string[], documentId: string): Promise<void> {
    for (const staleKey of objectKeys) {
      await this.objects.delete(staleKey).catch((error: unknown) => {
        this.logger.failure('upload.stale_object_not_deleted', error, { documentId });
      });
    }
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
   * Makes the uploaded object the document's PDF, in one transaction with the
   * quota accounting. Returns the bucket keys of the objects it replaced.
   */
  private commitPdf(
    auth: AuthContext,
    documentDbId: number,
    file: StoredObject,
  ): Promise<string[]> {
    return this.database.$transaction(async (tx) => {
      // Every writer of a document's files and pages (uploads, page changes, the OCR
      // commit, purge) locks the document row first, so the lists and sizes below are current.
      await lockDocument(tx, auth, documentDbId);
      const files = await tx.fileObject.findMany({ where: { documentId: documentDbId } });
      // All stored PDF variants count towards the quota, and all of them are replaced.
      // (Page originals live in document_pages and are not touched by a new PDF.)
      const previousBytes = files.reduce((sum, entry) => sum + entry.sizeBytes, 0n);
      await chargeStorage(tx, auth, BigInt(file.sizeBytes) - previousBytes);
      // A new PDF invalidates the searchable PDF derived from the old one.
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

  /**
   * Makes the uploaded object the page's original, in one transaction with the
   * quota accounting and `pagesUpdatedAt`. Returns the bucket key it replaced, if any.
   */
  private commitOriginal(
    auth: AuthContext,
    documentDbId: number,
    pageId: string,
    original: StoredObject,
  ): Promise<string[]> {
    return this.database.$transaction(async (tx) => {
      await lockDocument(tx, auth, documentDbId);
      const page = await tx.documentPage.findUnique({
        where: { documentId_publicId: { documentId: documentDbId, publicId: pageId } },
      });
      if (page === null) {
        // Removed from the page list while the upload was in flight.
        throw new AppError('NOT_FOUND');
      }
      await chargeStorage(tx, auth, BigInt(original.sizeBytes) - (page.originalSizeBytes ?? 0n));
      const now = new Date();
      await tx.documentPage.update({
        where: { id: page.id },
        data: {
          originalObjectId: original.objectId,
          originalObjectKey: original.objectKey,
          originalSizeBytes: BigInt(original.sizeBytes),
          originalSha256: Buffer.from(original.sha256Hex, 'hex'),
          updatedAt: now,
        },
      });
      await tx.document.update({ where: { id: documentDbId }, data: { pagesUpdatedAt: now } });
      return page.originalObjectKey === null ? [] : [page.originalObjectKey];
    });
  }
}

/**
 * Adds `delta` to the owner's storage usage. The quota is enforced by the
 * UPDATE itself, so two parallel uploads cannot both slip under it.
 */
async function chargeStorage(
  tx: DatabaseTransaction,
  auth: AuthContext,
  delta: bigint,
): Promise<void> {
  const accounted = await tx.$executeRaw`
    UPDATE users SET storage_used_bytes = storage_used_bytes + ${delta}
    WHERE id = ${auth.userId}
      AND (${delta} <= 0 OR storage_quota_bytes IS NULL OR storage_used_bytes + ${delta} <= storage_quota_bytes)`;
  if (accounted !== 1) {
    throw new AppError('QUOTA_EXCEEDED');
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
