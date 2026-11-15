package app.scanova.core.data.sync

import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.documents.decodeTags
import app.scanova.core.data.documents.encodeTags
import app.scanova.core.data.documents.toDomain
import app.scanova.core.data.documents.toLocal
import app.scanova.core.data.storage.DocumentFiles
import app.scanova.core.data.util.parseInstantOrNull
import app.scanova.core.database.dao.DocumentDao
import app.scanova.core.database.dao.SyncMetaDao
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.database.entity.LocalSyncState
import app.scanova.core.database.entity.PageEntity
import app.scanova.core.database.entity.SyncMetaEntity
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [SyncStore] and [PageStore] on top of Room. */
internal class RoomSyncStore(
    private val documents: DocumentDao,
    private val meta: SyncMetaDao,
    private val documentFiles: DocumentFiles,
    private val files: SyncFiles,
    private val clock: TimeSource,
    private val io: CoroutineContext = Dispatchers.IO,
) : SyncStore, PageStore {

    override suspend fun pendingPush(): List<SyncDocument> = documents.getPendingPush().map { it.toSync() }

    override suspend fun pendingDelete(): List<SyncDocument> = documents.getPendingDelete().map { it.toSync() }

    override suspend fun get(id: String): SyncDocument? = documents.getById(id)?.toSync()

    override suspend fun transition(id: String, event: SyncEvent, change: (SyncProgress) -> SyncProgress): Boolean {
        var applied = false
        documents.modify(id) { entity ->
            val next = SyncStateMachine.next(entity.syncState.toDomain(), event) ?: return@modify entity
            applied = true
            entity.withProgress(change(entity.progress())).copy(syncState = next.toLocal())
        }
        return applied
    }

    override suspend fun updateProgress(id: String, change: (SyncProgress) -> SyncProgress): Boolean =
        documents.modify(id) { entity -> entity.withProgress(change(entity.progress())) } != null

    override suspend fun remove(id: String) {
        documentFiles.deleteDocument(id)
    }

    override suspend fun applyRemote(remote: RemoteDocument) {
        val now = clock.now().toEpochMilli()
        val updatedAt = parseInstantOrNull(remote.updatedAtIso)?.toEpochMilli() ?: now
        var outdatedPdf: String? = null
        val existing = documents.modify(remote.id) { entity ->
            // Only a fully synced row mirrors the server; anything else is local work in progress.
            if (entity.syncState != LocalSyncState.SYNCED) return@modify entity
            val change = remoteContentChange(
                localSha256 = entity.contentSha256,
                pagesDirty = entity.pagesDirty,
                pagesStale = entity.pagesStale,
                localPagesUpdatedAt = entity.remotePagesUpdatedAt,
                remote = remote,
            )
            if (change.pdfReplaced) outdatedPdf = entity.pdfFile
            entity.copy(
                title = if (entity.metadataDirty) entity.title else remote.title,
                tags = if (entity.metadataDirty) entity.tags else encodeTags(remote.tags),
                // Page changes not sent yet (a server without page backup) know the real count.
                pageCount = if (change.pagesDirty) entity.pageCount else remote.pageCount,
                sizeBytes = remote.sizeBytes ?: entity.sizeBytes,
                contentSha256 = if (change.pdfReplaced) remote.contentSha256 else entity.contentSha256,
                // Edited on another device: the PDF here is out of date. The new one is fetched
                // when the document is opened, and the pages are restored from it.
                pdfFile = if (change.pdfReplaced) null else entity.pdfFile,
                pagesDirty = change.pagesDirty,
                pagesStale = change.pagesStale,
                ocrStatus = remote.ocrStatus,
                hasSearchablePdf = remote.hasSearchablePdf,
                updatedAt = updatedAt,
            )
        }
        outdatedPdf?.let { name -> withContext(io) { files.delete(name) } }
        if (existing != null) return
        documents.insert(
            DocumentEntity(
                id = remote.id,
                title = remote.title,
                tags = encodeTags(remote.tags),
                pageCount = remote.pageCount,
                sizeBytes = remote.sizeBytes,
                contentSha256 = remote.contentSha256,
                capturedAt = parseInstantOrNull(remote.capturedAtIso)?.toEpochMilli() ?: now,
                createdAt = parseInstantOrNull(remote.createdAtIso)?.toEpochMilli() ?: now,
                updatedAt = updatedAt,
                ocrText = "",
                // The PDF stays on the server until the user opens the document;
                // the pages are restored in the background (pagesStale).
                pdfFile = null,
                syncState = LocalSyncState.SYNCED,
                remoteCreated = true,
                fileUploaded = true,
                metadataDirty = false,
                ocrStatus = remote.ocrStatus,
                hasSearchablePdf = remote.hasSearchablePdf,
                lastErrorCode = null,
                syncAttempts = 0,
                pagesStale = true,
            ),
        )
    }

    override suspend fun setOcrText(id: String, text: String) {
        documents.modify(id) { it.copy(ocrText = text) }
    }

    override suspend fun pullCursor(): String? = meta.get(PULL_CURSOR)

    override suspend fun setPullCursor(cursor: String) {
        meta.put(SyncMetaEntity(PULL_CURSOR, cursor))
    }

    // ── Pages ───────────────────────────────────────────────────────────

    override suspend fun pagesToPush(): List<SyncDocument> = documents.getPagesToPush().map { it.toSync() }

    override suspend fun pages(documentId: String): List<SyncPage> = documents.getPages(documentId).map { it.toSync() }

    override suspend fun updatePage(pageId: String, change: (SyncPage) -> SyncPage): Boolean =
        documents.modifyPage(pageId) { page -> page.withSync(change(page.toSync())) } != null

    override suspend fun pagesPushed(documentId: String, remotePagesUpdatedAt: String?) {
        documents.modify(documentId) { entity ->
            entity.copy(
                pagesDirty = false,
                // The server now has exactly the pages this phone has.
                pagesStale = false,
                remotePagesUpdatedAt = remotePagesUpdatedAt ?: entity.remotePagesUpdatedAt,
            )
        }
    }

    override suspend fun pagesToRestore(): List<SyncDocument> = documents.getPagesToRestore().map { it.toSync() }

    override suspend fun queueDocumentsWithoutPages() {
        if (meta.get(PAGES_BACKFILL) != null) return
        documents.markPagelessDocumentsStale()
        meta.put(SyncMetaEntity(PAGES_BACKFILL, "1"))
    }

    override suspend fun restorePages(restore: PageRestore): List<String>? {
        // Page ids are global on the phone. One that another document here already uses
        // cannot be restored without breaking that document; leave this one as it is.
        val ids = restore.pages.map { it.id }
        val taken = ids.chunked(ID_QUERY_CHUNK).sumOf { documents.countPagesElsewhere(restore.documentId, it) }
        if (taken > 0) {
            stopRestoring(restore.documentId)
            return null
        }

        var replacedPdf: String? = null
        val previous = documents.restorePages(restore.documentId, restore.pages.map { it.toEntity() }) { entity ->
            val stillWanted = entity.syncState == LocalSyncState.SYNCED &&
                !entity.pagesDirty &&
                entity.contentSha256 == restore.contentSha256
            if (!stillWanted) {
                null
            } else {
                if (restore.pdfFile != null && entity.pdfFile != restore.pdfFile) replacedPdf = entity.pdfFile
                entity.copy(
                    pageCount = restore.pages.size,
                    pdfFile = restore.pdfFile ?: entity.pdfFile,
                    pagesStale = false,
                    remotePagesUpdatedAt = restore.remotePagesUpdatedAt,
                )
            }
        } ?: return null
        return previous.flatMap { listOfNotNull(it.imageFile, it.originalFile) } + listOfNotNull(replacedPdf)
    }

    override suspend fun stopRestoring(documentId: String) {
        documents.modify(documentId) { it.copy(pagesStale = false) }
    }

    override suspend fun missingOriginals(): List<SyncPage> = documents.getMissingOriginals().map { it.toSync() }

    override suspend fun attachOriginal(pageId: String, sha256Hex: String, file: String): Boolean {
        var attached = false
        documents.modifyPage(pageId) { page ->
            if (page.originalFile == null && page.originalSha256 == sha256Hex) {
                attached = true
                page.copy(originalFile = file, originalUploaded = true)
            } else {
                page
            }
        }
        return attached
    }

    // ── Mapping ─────────────────────────────────────────────────────────

    private fun DocumentEntity.toSync(): SyncDocument = SyncDocument(
        id = id,
        title = title,
        tags = decodeTags(tags),
        pageCount = pageCount,
        capturedAtIso = Instant.ofEpochMilli(capturedAt).toString(),
        ocrText = ocrText,
        sizeBytes = sizeBytes,
        contentSha256 = contentSha256,
        pdfFile = pdfFile,
        state = syncState.toDomain(),
        remoteCreated = remoteCreated,
        fileUploaded = fileUploaded,
        metadataDirty = metadataDirty,
        lastErrorCode = lastErrorCode,
        pagesDirty = pagesDirty,
        pagesStale = pagesStale,
        remotePagesUpdatedAt = remotePagesUpdatedAt,
        hasSearchablePdf = hasSearchablePdf,
    )

    private fun DocumentEntity.progress(): SyncProgress = SyncProgress(
        title = title,
        tags = decodeTags(tags),
        remoteCreated = remoteCreated,
        fileUploaded = fileUploaded,
        metadataDirty = metadataDirty,
        lastErrorCode = lastErrorCode,
        syncAttempts = syncAttempts,
        ocrStatus = ocrStatus,
        hasSearchablePdf = hasSearchablePdf,
    )

    /** Title and tags are read-only for the sync engine; only bookkeeping is written back. */
    private fun DocumentEntity.withProgress(progress: SyncProgress): DocumentEntity = copy(
        remoteCreated = progress.remoteCreated,
        fileUploaded = progress.fileUploaded,
        metadataDirty = progress.metadataDirty,
        lastErrorCode = progress.lastErrorCode,
        syncAttempts = progress.syncAttempts,
        ocrStatus = progress.ocrStatus,
        hasSearchablePdf = progress.hasSearchablePdf,
    )

    private fun PageEntity.toSync(): SyncPage = SyncPage(
        id = id,
        documentId = documentId,
        position = position,
        imageFile = imageFile,
        width = width,
        height = height,
        ocrText = ocrText,
        editJson = editJson,
        originalFile = originalFile,
        originalSha256 = originalSha256,
        originalSizeBytes = originalSizeBytes,
        originalUploaded = originalUploaded,
    )

    private fun SyncPage.toEntity(): PageEntity = PageEntity(
        id = id,
        documentId = documentId,
        position = position,
        imageFile = imageFile,
        width = width,
        height = height,
        ocrText = ocrText,
        originalFile = originalFile,
        originalSha256 = originalSha256,
        originalSizeBytes = originalSizeBytes,
        originalUploaded = originalUploaded,
        editJson = editJson,
    )

    /** Sync may change only the bookkeeping of an existing page, never its place, image or recipe. */
    private fun PageEntity.withSync(page: SyncPage): PageEntity = copy(
        originalSha256 = page.originalSha256,
        originalSizeBytes = page.originalSizeBytes,
        originalUploaded = page.originalUploaded,
    )

    private companion object {
        const val PULL_CURSOR = "documents.pull_cursor"

        /** Set once the documents restored before page restore existed were queued for it. */
        const val PAGES_BACKFILL = "pages.restore_backfill.v1"

        /** Stays below SQLite's limit on bound variables per statement. */
        const val ID_QUERY_CHUNK = 500
    }
}
