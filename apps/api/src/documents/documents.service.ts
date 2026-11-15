import { once } from 'node:events';
import type { Readable } from 'node:stream';
import { Inject, Injectable } from '@nestjs/common';
import type {
  CreateDocumentRequest,
  Document as DocumentDto,
  DocumentText as DocumentTextDto,
  ListDocumentsQuery,
  UpdateDocumentRequest,
} from '@scanova/contracts/documents';
import {
  documentSearchTokens,
  openDocumentMetadata,
  openDocumentText,
  querySearchTokens,
  sealDocumentMetadata,
  sealDocumentText,
} from '@scanova/crypto/document';
import { deriveObjectKey } from '@scanova/crypto/hierarchy';
import { zeroize } from '@scanova/crypto/zeroize';
import {
  Prisma,
  type DatabaseClient,
  type DatabaseTransaction,
  type Document,
  type FileObject,
} from '@scanova/database/client';
import type { Keyring } from '@scanova/keyring/keyring';
import type { EncryptedObjectStore } from '@scanova/storage/encrypted';
import { z } from 'zod';
import { AppError } from '../common/app-error.js';
import type { AuthContext } from '../common/auth-context.js';
import { decodeCursor, encodeCursor } from '../common/cursor.js';
import { Page } from '../common/page.js';
import { AppLogger } from '@scanova/config/logger';
import { DATABASE, ENCRYPTED_STORE, KEYRING } from '../platform/tokens.js';

export type DocumentWithFiles = Document & { readonly files: FileObject[] };

export interface OpenedFile {
  readonly stream: Readable;
  readonly sizeBytes: number;
  readonly sha256Hex: string;
}

const CursorSchema = z.object({
  u: z.iso.datetime(),
  i: z.uuid(),
  d: z.enum(['asc', 'desc']),
});

const SEARCH_TOKEN_INSERT_BATCH = 5000;

/** Documents of the signed-in user. Every query is scoped to `auth.userId`: another user's id is `NOT_FOUND`. */
@Injectable()
export class DocumentsService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(ENCRYPTED_STORE) private readonly objects: EncryptedObjectStore,
    private readonly logger: AppLogger,
  ) {}

  /**
   * Creates a document from the phone's metadata. The id is client-generated, so
   * a retry of the same request returns the existing document (`created: false`).
   * An id that belongs to someone else is `ID_CONFLICT`.
   */
  public async create(
    auth: AuthContext,
    request: CreateDocumentRequest,
  ): Promise<{ document: DocumentDto; created: boolean }> {
    const existing = await this.findAnyOwner(request.id);
    if (existing !== null) {
      return { document: await this.toDto(auth, this.assertOwned(auth, existing)), created: false };
    }

    // Create the key before the row it protects (§5.2). Idempotent, so a retry reuses it.
    await this.keyring.createDocumentKey(auth.userPublicId, request.id);
    const tags = request.tags ?? [];
    const text = request.clientOcrText ?? null;
    const sealed = await this.keyring.useDocumentKey(auth.userPublicId, request.id, (key) => ({
      ...sealDocumentMetadata(key, request.id, { title: request.title, tags }),
      textEnc: text === null ? null : sealDocumentText(key, request.id, text),
    }));
    const tokens = await this.keyring.useIndexKey(auth.userPublicId, (key) =>
      documentSearchTokens(key, { title: request.title, tags, text }),
    );

    try {
      const document = await this.database.$transaction(async (tx) => {
        const created = await tx.document.create({
          data: {
            publicId: request.id,
            userId: auth.userId,
            titleEnc: sealed.titleEnc,
            tagsEnc: sealed.tagsEnc,
            pageCount: request.pageCount,
            capturedAt: new Date(request.capturedAt),
            ...(sealed.textEnc === null
              ? {}
              : { text: { create: { textEnc: sealed.textEnc, source: 'CLIENT' } } }),
          },
          include: { files: true },
        });
        await this.replaceSearchTokens(tx, created.id, tokens);
        return created;
      });
      return { document: await this.toDto(auth, document), created: true };
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        // Lost a race against a request with the same id: answer as for an existing document.
        const winner = await this.findAnyOwner(request.id);
        if (winner !== null) {
          return {
            document: await this.toDto(auth, this.assertOwned(auth, winner)),
            created: false,
          };
        }
      }
      throw error;
    }
  }

  /**
   * Lists documents. Without `updatedSince`: live documents, most recently
   * changed first. With it: everything changed at or after that instant, oldest
   * change first and including deleted documents, so a device can sync removals
   * and resume from the last `updatedAt` it saw. `q` narrows to documents that
   * contain every query word (blind index).
   */
  public async list(auth: AuthContext, query: ListDocumentsQuery): Promise<Page<DocumentDto>> {
    const direction = query.updatedSince === undefined ? 'desc' : 'asc';
    const after = query.cursor === undefined ? null : decodeCursor(query.cursor, CursorSchema);
    if (after !== null && after.d !== direction) {
      throw new AppError('VALIDATION_FAILED', {
        details: [{ field: 'cursor', message: 'This cursor belongs to a different query.' }],
      });
    }

    const conditions: Prisma.DocumentWhereInput[] = [{ userId: auth.userId }];
    if (query.updatedSince === undefined) {
      conditions.push({ deletedAt: null });
    } else {
      // Inclusive: a client sends back the largest `updatedAt` it has seen, and a change
      // in that same millisecond must not be skipped. Seeing a document twice is harmless.
      conditions.push({ updatedAt: { gte: new Date(query.updatedSince) } });
    }
    if (query.q !== undefined) {
      conditions.push({ id: { in: await this.search(auth, query.q) } });
    }
    if (after !== null) {
      const at = new Date(after.u);
      conditions.push(
        direction === 'asc'
          ? { OR: [{ updatedAt: { gt: at } }, { updatedAt: at, publicId: { gt: after.i } }] }
          : { OR: [{ updatedAt: { lt: at } }, { updatedAt: at, publicId: { lt: after.i } }] },
      );
    }

    const rows = await this.database.document.findMany({
      where: { AND: conditions },
      orderBy: [{ updatedAt: direction }, { publicId: direction }],
      take: query.limit + 1,
      include: { files: true },
    });
    const page = rows.slice(0, query.limit);
    const last = page.at(-1);
    return new Page(
      await this.toDtos(auth, page),
      rows.length > query.limit && last !== undefined
        ? encodeCursor({ u: last.updatedAt.toISOString(), i: last.publicId, d: direction })
        : null,
    );
  }

  /** One document. A deleted one is still returned (with `deletedAt`) so devices can sync the removal. */
  public async get(auth: AuthContext, documentId: string): Promise<DocumentDto> {
    return this.toDto(auth, await this.owned(auth, documentId, { includeDeleted: true }));
  }

  public async update(
    auth: AuthContext,
    documentId: string,
    request: UpdateDocumentRequest,
  ): Promise<DocumentDto> {
    const document = await this.owned(auth, documentId);
    const storedText = await this.database.documentText.findUnique({
      where: { documentId: document.id },
    });

    // The search index covers title, tags and text together, so it is rebuilt from all three.
    const next = await this.keyring.useDocumentKey(auth.userPublicId, documentId, (key) => {
      const current = openDocumentMetadata(key, documentId, document);
      const metadata = {
        title: request.title ?? current.title,
        tags: request.tags ?? current.tags,
      };
      return {
        metadata,
        sealed: sealDocumentMetadata(key, documentId, metadata),
        text: storedText === null ? null : openDocumentText(key, documentId, storedText.textEnc),
      };
    });
    const tokens = await this.keyring.useIndexKey(auth.userPublicId, (key) =>
      documentSearchTokens(key, { ...next.metadata, text: next.text }),
    );
    const updated = await this.database.$transaction(async (tx) => {
      await this.replaceSearchTokens(tx, document.id, tokens);
      return tx.document.update({
        where: { id: document.id },
        data: { titleEnc: next.sealed.titleEnc, tagsEnc: next.sealed.tagsEnc },
        include: { files: true },
      });
    });
    return this.toDto(auth, updated);
  }

  /** Soft delete: the document moves to the trash and is purged after the retention. Idempotent. */
  public async softDelete(auth: AuthContext, documentId: string): Promise<void> {
    const document = await this.owned(auth, documentId, { includeDeleted: true });
    if (document.deletedAt === null) {
      await this.database.document.update({
        where: { id: document.id },
        data: { deletedAt: new Date() },
      });
    }
  }

  public async getText(auth: AuthContext, documentId: string): Promise<DocumentTextDto> {
    const document = await this.owned(auth, documentId);
    const stored = await this.database.documentText.findUnique({
      where: { documentId: document.id },
    });
    if (stored === null) {
      throw new AppError('NOT_FOUND');
    }
    const text = await this.keyring.useDocumentKey(auth.userPublicId, documentId, (key) =>
      openDocumentText(key, documentId, stored.textEnc),
    );
    return { text, source: stored.source };
  }

  /**
   * Opens a stored file for download as a plaintext stream. The first chunk is
   * decrypted and verified before this returns, so a missing object or a key
   * problem is reported as a proper error instead of a broken download.
   */
  public async openFile(
    auth: AuthContext,
    documentId: string,
    variant: 'ORIGINAL' | 'SEARCHABLE',
  ): Promise<OpenedFile> {
    const document = await this.owned(auth, documentId);
    const file = document.files.find((candidate) => candidate.variant === variant);
    if (file === undefined) {
      throw new AppError('NOT_FOUND');
    }
    const streamKey = await this.keyring.useDocumentKey(auth.userPublicId, documentId, (key) =>
      deriveObjectKey(key, file.publicId),
    );
    let stream: Readable;
    try {
      stream = await this.objects.get({
        objectKey: file.objectKey,
        objectId: file.publicId,
        streamKey,
      });
    } finally {
      // The decrypting stream keeps its own copy for as long as it runs.
      zeroize(streamKey);
    }
    try {
      await once(stream, 'readable');
    } catch (error) {
      stream.destroy();
      throw error;
    }
    return {
      stream,
      sizeBytes: Number(file.sizeBytes),
      sha256Hex: Buffer.from(file.sha256).toString('hex'),
    };
  }

  /** A live document of the caller, with its files. Used by the upload path as well. */
  public async owned(
    auth: AuthContext,
    documentId: string,
    options: { includeDeleted?: boolean } = {},
  ): Promise<DocumentWithFiles> {
    const document = await this.database.document.findFirst({
      where: {
        publicId: documentId,
        userId: auth.userId,
        ...(options.includeDeleted === true ? {} : { deletedAt: null }),
      },
      include: { files: true },
    });
    if (document === null) {
      throw new AppError('NOT_FOUND');
    }
    return document;
  }

  public async toDto(auth: AuthContext, document: DocumentWithFiles): Promise<DocumentDto> {
    const [dto] = await this.toDtos(auth, [document]);
    if (dto === undefined) {
      // Its key is gone: the document is being purged.
      throw new AppError('NOT_FOUND');
    }
    return dto;
  }

  private async toDtos(
    auth: AuthContext,
    documents: readonly DocumentWithFiles[],
  ): Promise<DocumentDto[]> {
    if (documents.length === 0) {
      return [];
    }
    return this.keyring.useDocumentKeys(
      auth.userPublicId,
      documents.map((document) => document.publicId),
      (keys) => {
        const dtos: DocumentDto[] = [];
        for (const document of documents) {
          const key = keys.get(document.publicId);
          if (key === undefined) {
            // A document whose key was already destroyed is on its way out; it is not listed.
            this.logger.event('warn', 'document.key_missing', { documentId: document.publicId });
            continue;
          }
          const metadata = openDocumentMetadata(key, document.publicId, document);
          const original = document.files.find((file) => file.variant === 'ORIGINAL');
          dtos.push({
            id: document.publicId,
            title: metadata.title,
            tags: metadata.tags,
            pageCount: document.pageCount,
            sizeBytes: original === undefined ? null : Number(original.sizeBytes),
            contentSha256:
              original === undefined ? null : Buffer.from(original.sha256).toString('hex'),
            status: document.status,
            ocrStatus: document.ocrStatus,
            hasSearchablePdf: document.files.some((file) => file.variant === 'SEARCHABLE'),
            capturedAt: document.capturedAt.toISOString(),
            createdAt: document.createdAt.toISOString(),
            updatedAt: document.updatedAt.toISOString(),
            deletedAt: document.deletedAt?.toISOString() ?? null,
          });
        }
        return dtos;
      },
    );
  }

  /** Internal ids of the caller's documents that contain every word of the query. */
  private async search(auth: AuthContext, text: string): Promise<number[]> {
    const tokens = await this.keyring.useIndexKey(auth.userPublicId, (key) =>
      querySearchTokens(key, text),
    );
    if (tokens.length === 0) {
      return [];
    }
    const matches = await this.database.searchToken.groupBy({
      by: ['documentId'],
      where: { token: { in: tokens }, document: { userId: auth.userId } },
      _count: { token: true },
      having: { token: { _count: { equals: tokens.length } } },
    });
    return matches.map((match) => match.documentId);
  }

  private async replaceSearchTokens(
    tx: DatabaseTransaction,
    documentDbId: number,
    tokens: readonly Buffer<ArrayBuffer>[],
  ): Promise<void> {
    await tx.searchToken.deleteMany({ where: { documentId: documentDbId } });
    for (let offset = 0; offset < tokens.length; offset += SEARCH_TOKEN_INSERT_BATCH) {
      await tx.searchToken.createMany({
        data: tokens
          .slice(offset, offset + SEARCH_TOKEN_INSERT_BATCH)
          .map((token) => ({ documentId: documentDbId, token })),
      });
    }
  }

  private findAnyOwner(documentId: string): Promise<DocumentWithFiles | null> {
    return this.database.document.findUnique({
      where: { publicId: documentId },
      include: { files: true },
    });
  }

  private assertOwned(auth: AuthContext, document: DocumentWithFiles): DocumentWithFiles {
    if (document.userId !== auth.userId) {
      throw new AppError('ID_CONFLICT');
    }
    return document;
  }
}
