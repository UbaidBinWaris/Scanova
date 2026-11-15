package app.scanova.core.data.sync

import app.scanova.core.common.result.AppResult
import app.scanova.core.common.result.map
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.network.api.CreateDocumentRequest
import app.scanova.core.network.api.DocumentDto
import java.io.InputStream

/** [SyncApi] on top of the real server client. */
internal class RemoteSyncApi(
    private val remote: ScanovaRemote,
) : SyncApi {

    override suspend fun create(document: SyncDocument, clientOcrText: String?): AppResult<RemoteDocument> =
        remote.createDocument(
            CreateDocumentRequest(
                id = document.id,
                title = document.title,
                pageCount = document.pageCount,
                capturedAt = document.capturedAtIso,
                tags = document.tags.takeIf { it.isNotEmpty() },
                clientOcrText = clientOcrText,
            ),
        ).map { it.toRemote() }

    override suspend fun upload(
        id: String,
        sha256Hex: String,
        length: Long,
        open: () -> InputStream,
    ): AppResult<RemoteDocument> = remote.uploadFile(id, sha256Hex, length, open).map { it.toRemote() }

    override suspend fun updateMetadata(id: String, title: String, tags: List<String>): AppResult<RemoteDocument> =
        remote.updateDocument(id, title, tags).map { it.toRemote() }

    override suspend fun delete(id: String): AppResult<Unit> = remote.deleteDocument(id).map { }

    override suspend fun list(cursor: String?, updatedSince: String?): AppResult<RemotePage> =
        remote.listDocuments(PAGE_SIZE, cursor, updatedSince).map { page ->
            RemotePage(page.items.map { it.toRemote() }, page.nextCursor)
        }

    override suspend fun text(id: String): AppResult<String> = remote.documentText(id).map { it.text }

    private fun DocumentDto.toRemote(): RemoteDocument = RemoteDocument(
        id = id,
        title = title,
        tags = tags,
        pageCount = pageCount,
        sizeBytes = sizeBytes,
        contentSha256 = contentSha256,
        ready = status == STATUS_READY,
        ocrStatus = ocrStatus,
        hasSearchablePdf = hasSearchablePdf,
        capturedAtIso = capturedAt,
        createdAtIso = createdAt,
        updatedAtIso = updatedAt,
        deleted = deletedAt != null,
    )

    private companion object {
        const val PAGE_SIZE = 100
        const val STATUS_READY = "READY"
    }
}
