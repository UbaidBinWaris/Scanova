import { Inject, Injectable } from '@nestjs/common';
import { AppLogger } from '@scanova/config/logger';
import {
  PageEditSchema,
  type DocumentPage as DocumentPageDto,
  type DocumentPages,
  type ReplacePagesRequest,
} from '@scanova/contracts/documents';
import { openPageEdits, sealPageEdits } from '@scanova/crypto/document';
import type { DatabaseClient, DatabaseTransaction, DocumentPage } from '@scanova/database/client';
import type { Keyring } from '@scanova/keyring/keyring';
import type { EncryptedObjectStore } from '@scanova/storage/encrypted';
import { AppError } from '../common/app-error.js';
import type { AuthContext } from '../common/auth-context.js';
import { DATABASE, ENCRYPTED_STORE, KEYRING } from '../platform/tokens.js';
import { DocumentsService, type DocumentWithFiles, type OpenedFile } from './documents.service.js';

const INSERT_BATCH = 1000;
/** A long document reordered page by page is many small updates; give them time. */
const PAGE_TRANSACTION = { maxWait: 10_000, timeout: 60_000 } as const;

/** A page as the request describes it, in its final order. */
interface WantedPage {
  readonly id: string;
  readonly position: number;
  readonly width: number;
  readonly height: number;
  /** The recipe as JSON text, or null. */
  readonly editJson: string | null;
}

/**
 * The pages of a document (§6, §7.3): per page its order, size, encrypted edit
 * recipe and (uploaded separately, see UploadService) its original photo.
 *
 * Every writer of a document's pages locks the document row first (like every
 * writer of its files), so the page list, the originals and the storage
 * accounting can never be seen half-changed.
 */
@Injectable()
export class PagesService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(ENCRYPTED_STORE) private readonly objects: EncryptedObjectStore,
    private readonly documents: DocumentsService,
    private readonly logger: AppLogger,
  ) {}

  /** The page list in order, with decrypted recipes. */
  public async list(auth: AuthContext, documentId: string): Promise<DocumentPages> {
    const document = await this.documents.owned(auth, documentId);
    const pages = await this.database.documentPage.findMany({
      where: { documentId: document.id },
      orderBy: { position: 'asc' },
    });
    return {
      pagesUpdatedAt: document.pagesUpdatedAt?.toISOString() ?? null,
      pages: await this.toDtos(auth, documentId, pages),
    };
  }

  /** One page, with its decrypted recipe. */
  public async get(
    auth: AuthContext,
    documentId: string,
    pageId: string,
  ): Promise<DocumentPageDto> {
    const { page } = await this.owned(auth, documentId, pageId);
    const [dto] = await this.toDtos(auth, documentId, [page]);
    if (dto === undefined) {
      throw new AppError('NOT_FOUND');
    }
    return dto;
  }

  /** A page of a live document of the caller. Another user's document, or a page not in the list, is `NOT_FOUND`. */
  public async owned(
    auth: AuthContext,
    documentId: string,
    pageId: string,
  ): Promise<{ document: DocumentWithFiles; page: DocumentPage }> {
    const document = await this.documents.owned(auth, documentId);
    const page = await this.database.documentPage.findUnique({
      where: { documentId_publicId: { documentId: document.id, publicId: pageId } },
    });
    if (page === null) {
      throw new AppError('NOT_FOUND');
    }
    return { document, page };
  }

  /**
   * Makes the request the document's page list. Pages that are not in it are
   * deleted together with their originals (and their storage is given back);
   * new pages are added without an original; recipes are encrypted again only
   * when they changed. Sending the same list twice changes nothing, not even
   * `pagesUpdatedAt`.
   */
  public async replace(
    auth: AuthContext,
    documentId: string,
    request: ReplacePagesRequest,
  ): Promise<DocumentPages> {
    const document = await this.documents.owned(auth, documentId);
    // The phone's order, numbered 0, 1, 2, … so positions are always contiguous.
    const wanted: WantedPage[] = [...request.pages]
      .sort((a, b) => a.position - b.position)
      .map((page, index) => ({
        id: page.id,
        position: index,
        width: page.width,
        height: page.height,
        editJson: page.edit === null || page.edit === undefined ? null : JSON.stringify(page.edit),
      }));

    // Vault and cipher work happens before the transaction, never while the row is locked:
    // decrypt what is stored (to recognise unchanged recipes) and seal what was sent.
    const before = await this.database.documentPage.findMany({
      where: { documentId: document.id },
    });
    const storedEdits = before.filter(
      (page): page is DocumentPage & { editEnc: Uint8Array<ArrayBuffer> } => page.editEnc !== null,
    );
    const sentEdits = wanted.filter(
      (page): page is WantedPage & { editJson: string } => page.editJson !== null,
    );
    const recipes = await this.keyring.useDocumentKey(auth.userPublicId, documentId, (key) => {
      const opened = openPageEdits(
        key,
        storedEdits.map((page) => ({ pageId: page.publicId, editEnc: page.editEnc })),
      );
      const sealed = sealPageEdits(
        key,
        sentEdits.map((page) => ({ pageId: page.id, json: page.editJson })),
      );
      return {
        stored: new Map(storedEdits.map((page, index) => [page.publicId, opened[index]!])),
        sealed: new Map(sentEdits.map((page, index) => [page.id, sealed[index]!])),
      };
    });
    const seen = new Map(before.map((page) => [page.publicId, page]));

    const now = new Date();
    const releasedKeys = await this.database.$transaction(async (tx) => {
      await lockDocument(tx, auth, document.id);
      const current = await tx.documentPage.findMany({ where: { documentId: document.id } });
      const currentById = new Map(current.map((page) => [page.publicId, page]));
      const wantedIds = new Set(wanted.map((page) => page.id));
      const removed = current.filter((page) => !wantedIds.has(page.publicId));
      let changed = removed.length > 0;

      const added = wanted.filter((page) => !currentById.has(page.id));
      for (let offset = 0; offset < added.length; offset += INSERT_BATCH) {
        await tx.documentPage.createMany({
          data: added.slice(offset, offset + INSERT_BATCH).map((page) => ({
            publicId: page.id,
            documentId: document.id,
            position: page.position,
            width: page.width,
            height: page.height,
            editEnc: page.editJson === null ? null : recipes.sealed.get(page.id)!,
            createdAt: now,
            updatedAt: now,
          })),
        });
        changed = true;
      }

      for (const page of wanted) {
        const editEnc = page.editJson === null ? null : recipes.sealed.get(page.id)!;
        const row = currentById.get(page.id);
        if (row === undefined) {
          continue;
        }
        // The stored recipe is only trusted as "unchanged" if the row is still the one decrypted above.
        const decrypted = seen.get(page.id);
        const sameEdit =
          decrypted !== undefined &&
          decrypted.updatedAt.getTime() === row.updatedAt.getTime() &&
          (recipes.stored.get(page.id) ?? null) === page.editJson;
        if (
          sameEdit &&
          row.position === page.position &&
          row.width === page.width &&
          row.height === page.height
        ) {
          continue;
        }
        await tx.documentPage.update({
          where: { id: row.id },
          data: {
            position: page.position,
            width: page.width,
            height: page.height,
            ...(sameEdit ? {} : { editEnc }),
            updatedAt: now,
          },
        });
        changed = true;
      }

      if (removed.length > 0) {
        await tx.documentPage.deleteMany({ where: { id: { in: removed.map((page) => page.id) } } });
        // The originals of removed pages no longer count towards the owner's storage.
        const freed = removed.reduce((sum, page) => sum + (page.originalSizeBytes ?? 0n), 0n);
        if (freed > 0n) {
          await tx.$executeRaw`
            UPDATE users SET storage_used_bytes = GREATEST(storage_used_bytes - ${freed}, 0)
            WHERE id = ${auth.userId}`;
        }
      }
      if (changed || document.pageCount !== wanted.length) {
        await tx.document.update({
          where: { id: document.id },
          data: { pageCount: wanted.length, pagesUpdatedAt: now },
        });
      }
      return removed.flatMap((page) =>
        page.originalObjectKey === null ? [] : [page.originalObjectKey],
      );
    }, PAGE_TRANSACTION);

    // Committed: the originals of removed pages are garbage now.
    for (const key of releasedKeys) {
      await this.objects.delete(key).catch((error: unknown) => {
        this.logger.failure('pages.stale_original_not_deleted', error, { documentId });
      });
    }
    return this.list(auth, documentId);
  }

  /** Opens a page's original for download (see `DocumentsService.openObject`). */
  public async openOriginal(
    auth: AuthContext,
    documentId: string,
    pageId: string,
  ): Promise<OpenedFile> {
    const { page } = await this.owned(auth, documentId, pageId);
    if (
      page.originalObjectId === null ||
      page.originalObjectKey === null ||
      page.originalSizeBytes === null ||
      page.originalSha256 === null
    ) {
      throw new AppError('NOT_FOUND');
    }
    return this.documents.openObject(auth, documentId, {
      objectId: page.originalObjectId,
      objectKey: page.originalObjectKey,
      sizeBytes: page.originalSizeBytes,
      sha256: page.originalSha256,
    });
  }

  private async toDtos(
    auth: AuthContext,
    documentId: string,
    pages: readonly DocumentPage[],
  ): Promise<DocumentPageDto[]> {
    const withEdits = pages.filter(
      (page): page is DocumentPage & { editEnc: Uint8Array<ArrayBuffer> } => page.editEnc !== null,
    );
    const opened =
      withEdits.length === 0
        ? new Map<string, string>()
        : await this.keyring.useDocumentKey(auth.userPublicId, documentId, (key) => {
            const json = openPageEdits(
              key,
              withEdits.map((page) => ({ pageId: page.publicId, editEnc: page.editEnc })),
            );
            return new Map(withEdits.map((page, index) => [page.publicId, json[index]!]));
          });
    return pages.map((page) => {
      const json = opened.get(page.publicId);
      return {
        id: page.publicId,
        position: page.position,
        width: page.width,
        height: page.height,
        edit: json === undefined ? null : PageEditSchema.parse(JSON.parse(json)),
        hasOriginal: page.originalObjectId !== null,
        originalSha256:
          page.originalSha256 === null ? null : Buffer.from(page.originalSha256).toString('hex'),
        originalSizeBytes: page.originalSizeBytes === null ? null : Number(page.originalSizeBytes),
        createdAt: page.createdAt.toISOString(),
        updatedAt: page.updatedAt.toISOString(),
      };
    });
  }
}

/**
 * Locks a live document of the caller for the rest of the transaction. Uploads,
 * the OCR commit, page changes and purge all take this lock first.
 */
export async function lockDocument(
  tx: DatabaseTransaction,
  auth: AuthContext,
  documentDbId: number,
): Promise<void> {
  const locked = await tx.$queryRaw<{ id: number }[]>`
    SELECT id FROM documents
    WHERE id = ${documentDbId} AND user_id = ${auth.userId} AND deleted_at IS NULL
    FOR UPDATE`;
  if (locked.length === 0) {
    // Deleted meanwhile.
    throw new AppError('NOT_FOUND');
  }
}
