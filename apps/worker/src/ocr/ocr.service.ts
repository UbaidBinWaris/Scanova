import { createHash, randomUUID } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, mkdtemp, open, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { Inject, Injectable } from '@nestjs/common';
import { AppLogger } from '@scanova/config/logger';
import type { WorkerConfig } from '@scanova/config/sections';
import {
  documentSearchTokens,
  openDocumentMetadata,
  sealDocumentText,
} from '@scanova/crypto/document';
import { deriveObjectKey } from '@scanova/crypto/hierarchy';
import { zeroize } from '@scanova/crypto/zeroize';
import type { DatabaseClient } from '@scanova/database/client';
import type { JobContext } from '@scanova/jobs/queue';
import { KeyringError } from '@scanova/keyring/errors';
import type { Keyring } from '@scanova/keyring/keyring';
import type { EncryptedObjectStore } from '@scanova/storage/encrypted';
import { objectKeyFor } from '@scanova/storage/object-keys';
import {
  DATABASE,
  ENCRYPTED_STORE,
  KEYRING,
  OCR_ENGINE,
  WORKER_CONFIG,
} from '../platform/tokens.js';
import { OcrError, type OcrEngine } from './ocr-engine.js';

/** Recognised text beyond this is cut: it only feeds search and the text endpoint. */
const MAX_TEXT_BYTES = 4 * 1024 * 1024;
const TOKEN_INSERT_BATCH = 5000;

export type OcrOutcome = 'DONE' | 'SKIPPED' | 'SUPERSEDED';

/**
 * Server OCR for one document (§1, §5.1): decrypt the original to the tmpfs
 * scratch directory → OCRmyPDF with a text sidecar → encrypt the searchable PDF
 * and the text → rebuild the blind index → delete the scratch files, always.
 */
@Injectable()
export class OcrService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(ENCRYPTED_STORE) private readonly objects: EncryptedObjectStore,
    @Inject(OCR_ENGINE) private readonly engine: OcrEngine,
    @Inject(WORKER_CONFIG) private readonly config: WorkerConfig,
    private readonly logger: AppLogger,
  ) {}

  public async process(
    documentId: string,
    context: Pick<JobContext, 'isFinalAttempt' | 'signal'>,
  ): Promise<OcrOutcome> {
    const document = await this.database.document.findUnique({
      where: { publicId: documentId },
      include: { files: true, user: { select: { id: true, publicId: true, deletedAt: true } } },
    });
    const original = document?.files.find((file) => file.variant === 'ORIGINAL');
    if (
      document === null ||
      original === undefined ||
      document.deletedAt !== null ||
      document.user.deletedAt !== null
    ) {
      return 'SKIPPED';
    }
    if (
      document.ocrStatus === 'DONE' &&
      document.files.some((file) => file.variant === 'SEARCHABLE')
    ) {
      // Already processed for this original (a job delivered twice).
      return 'SKIPPED';
    }
    const userId = document.user.publicId;

    await mkdir(this.config.ocr.tmpDir, { recursive: true, mode: 0o700 });
    const workDir = await mkdtemp(join(this.config.ocr.tmpDir, 'ocr-'));
    const inputPath = join(workDir, 'input.pdf');
    const outputPath = join(workDir, 'output.pdf');
    const sidecarPath = join(workDir, 'text.txt');
    let storedObjectKey: string | null = null;
    try {
      // 1. Decrypt the original onto the tmpfs. The only place plaintext leaves process memory.
      const inputKey = await this.keyring.useDocumentKey(userId, documentId, (key) =>
        deriveObjectKey(key, original.publicId),
      );
      try {
        const plaintext = await this.objects.get({
          objectKey: original.objectKey,
          objectId: original.publicId,
          streamKey: inputKey,
        });
        await pipeline(plaintext, createWriteStream(inputPath, { mode: 0o600 }));
      } finally {
        zeroize(inputKey);
      }

      // 2. OCR.
      await this.engine.run({
        inputPath,
        outputPath,
        sidecarPath,
        languages: this.config.ocr.languages,
        workDir,
        signal: context.signal,
      });

      // 3. Encrypt the searchable PDF under its own stream key and store it.
      const objectId = randomUUID();
      const objectKey = objectKeyFor({ userId, documentId, objectId });
      const hash = createHash('sha256');
      const hashing = new Transform({
        transform(chunk: Buffer, _encoding, done): void {
          hash.update(chunk);
          done(null, chunk);
        },
      });
      const outputKey = await this.keyring.useDocumentKey(userId, documentId, (key) =>
        deriveObjectKey(key, objectId),
      );
      try {
        const source = createReadStream(outputPath);
        source.on('error', (error) => hashing.destroy(error));
        source.pipe(hashing);
        await this.objects.put({ objectKey, objectId, streamKey: outputKey }, hashing);
        storedObjectKey = objectKey;
      } finally {
        zeroize(outputKey);
      }
      const sizeBytes = (await stat(outputPath)).size;
      const text = await readText(sidecarPath);

      // 4. Encrypt the text and rebuild the blind index over title, tags and the new text.
      const sealed = await this.keyring.useDocumentKey(userId, documentId, (key) => ({
        metadata: openDocumentMetadata(key, documentId, document),
        textEnc: text.length === 0 ? null : sealDocumentText(key, documentId, text),
      }));
      const tokens =
        sealed.textEnc === null
          ? null
          : await this.keyring.useIndexKey(userId, (key) =>
              documentSearchTokens(key, { ...sealed.metadata, text }),
            );

      // 5. Commit, unless the user replaced or deleted the file while we were working.
      const replaced = await this.database.$transaction(async (tx) => {
        // Same row lock as the upload commit, so the file list and sizes are current.
        await tx.$queryRaw`SELECT id FROM documents WHERE id = ${document.id} FOR UPDATE`;
        const current = await tx.document.findFirst({
          where: { id: document.id, deletedAt: null },
          include: { files: true },
        });
        if (
          current?.files.find((file) => file.variant === 'ORIGINAL')?.publicId !== original.publicId
        ) {
          return null;
        }
        const previous = current.files.filter((file) => file.variant === 'SEARCHABLE');
        // The searchable PDF counts towards the owner's storage, like the original.
        const delta = BigInt(sizeBytes) - previous.reduce((sum, file) => sum + file.sizeBytes, 0n);
        await tx.$executeRaw`
          UPDATE users SET storage_used_bytes = storage_used_bytes + ${delta}
          WHERE id = ${current.userId}`;
        await tx.fileObject.deleteMany({
          where: { documentId: document.id, variant: 'SEARCHABLE' },
        });
        await tx.fileObject.create({
          data: {
            publicId: objectId,
            documentId: document.id,
            variant: 'SEARCHABLE',
            objectKey,
            sizeBytes: BigInt(sizeBytes),
            sha256: hash.digest(),
          },
        });
        if (sealed.textEnc !== null && tokens !== null) {
          // Server OCR replaces the phone's text. An empty result keeps what was there.
          await tx.documentText.upsert({
            where: { documentId: document.id },
            create: { documentId: document.id, textEnc: sealed.textEnc, source: 'SERVER' },
            update: { textEnc: sealed.textEnc, source: 'SERVER' },
          });
          await tx.searchToken.deleteMany({ where: { documentId: document.id } });
          for (let offset = 0; offset < tokens.length; offset += TOKEN_INSERT_BATCH) {
            await tx.searchToken.createMany({
              data: tokens
                .slice(offset, offset + TOKEN_INSERT_BATCH)
                .map((token) => ({ documentId: document.id, token })),
            });
          }
        }
        await tx.document.update({ where: { id: document.id }, data: { ocrStatus: 'DONE' } });
        return previous.map((file) => file.objectKey);
      });
      if (replaced === null) {
        await this.objects.delete(objectKey).catch(() => undefined);
        return 'SUPERSEDED';
      }
      for (const staleKey of replaced) {
        await this.objects.delete(staleKey).catch((error: unknown) => {
          this.logger.failure('ocr.stale_object_not_deleted', error, { documentId });
        });
      }
      this.logger.info('ocr.done', { documentId, sizeBytes, textBytes: Buffer.byteLength(text) });
      return 'DONE';
    } catch (error) {
      if (storedObjectKey !== null) {
        await this.objects.delete(storedObjectKey).catch(() => undefined);
      }
      if (
        error instanceof KeyringError &&
        (error.code === 'KEY_NOT_FOUND' || error.code === 'KEY_DESTROYED')
      ) {
        // The document or its owner was deleted meanwhile: nothing left to do.
        return 'SKIPPED';
      }
      if (context.isFinalAttempt) {
        await this.database.document.updateMany({
          where: {
            id: document.id,
            ocrStatus: 'PENDING',
            files: { some: { publicId: original.publicId } },
          },
          data: { ocrStatus: 'FAILED' },
        });
        this.logger.event('warn', 'ocr.failed', {
          documentId,
          code: error instanceof OcrError ? error.code : 'ERROR',
        });
      }
      throw error;
    } finally {
      // Always: the scratch directory holds plaintext.
      await rm(workDir, { recursive: true, force: true });
    }
  }
}

/** Reads the sidecar text, cut to a sane size and without form feeds and trailing space. */
async function readText(path: string): Promise<string> {
  let handle;
  try {
    handle = await open(path, 'r');
  } catch {
    return '';
  }
  try {
    const buffer = Buffer.alloc(MAX_TEXT_BYTES);
    const { bytesRead } = await handle.read(buffer, 0, MAX_TEXT_BYTES, 0);
    // A cut in the middle of a multi-byte character becomes U+FFFD, which is not indexed.
    return buffer
      .subarray(0, bytesRead)
      .toString('utf8')
      .replaceAll('\f', '\n')
      .replace(/\[OCR skipped on page\(s\) [^\]]*\]/gu, '')
      .trim();
  } finally {
    await handle.close();
  }
}
