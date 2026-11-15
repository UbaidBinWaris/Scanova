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
import app.scanova.core.database.entity.SyncMetaEntity
import java.time.Instant

/** [SyncStore] on top of Room. */
internal class RoomSyncStore(
    private val documents: DocumentDao,
    private val meta: SyncMetaDao,
    private val documentFiles: DocumentFiles,
    private val clock: TimeSource,
) : SyncStore {

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
        val existing = documents.modify(remote.id) { entity ->
            // Only a fully synced row mirrors the server; anything else is local work in progress.
            if (entity.syncState != LocalSyncState.SYNCED) return@modify entity
            entity.copy(
                title = if (entity.metadataDirty) entity.title else remote.title,
                tags = if (entity.metadataDirty) entity.tags else encodeTags(remote.tags),
                pageCount = remote.pageCount,
                sizeBytes = remote.sizeBytes ?: entity.sizeBytes,
                ocrStatus = remote.ocrStatus,
                hasSearchablePdf = remote.hasSearchablePdf,
                updatedAt = updatedAt,
            )
        }
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
                // The file stays on the server until the user opens the document.
                pdfFile = null,
                syncState = LocalSyncState.SYNCED,
                remoteCreated = true,
                fileUploaded = true,
                metadataDirty = false,
                ocrStatus = remote.ocrStatus,
                hasSearchablePdf = remote.hasSearchablePdf,
                lastErrorCode = null,
                syncAttempts = 0,
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

    private companion object {
        const val PULL_CURSOR = "documents.pull_cursor"
    }
}
