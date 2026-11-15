package app.scanova.core.data.sync

import app.scanova.core.common.result.AppResult
import app.scanova.core.common.result.map
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.network.api.CreateDocumentRequest
import app.scanova.core.network.api.DocumentDto
import app.scanova.core.network.api.DocumentPageDto
import app.scanova.core.network.api.DocumentPagesDto
import app.scanova.core.network.api.PageInputDto
import java.io.InputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

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

    override suspend fun replacePages(id: String, pages: List<OutgoingPage>): AppResult<RemotePageList> =
        remote.replacePages(
            id,
            pages.map { page ->
                PageInputDto(
                    id = page.id,
                    position = page.position,
                    width = page.width,
                    height = page.height,
                    edit = page.editJson?.let(::parseRecipe),
                )
            },
        ).map { it.toRemote() }

    override suspend fun uploadOriginal(
        id: String,
        pageId: String,
        sha256Hex: String,
        length: Long,
        open: () -> InputStream,
    ): AppResult<RemotePageInfo> = remote.uploadPageOriginal(id, pageId, sha256Hex, length, open).map { it.toRemote() }

    override suspend fun pages(id: String): AppResult<RemotePageList> = remote.documentPages(id).map { it.toRemote() }

    override suspend fun <R> downloadPdf(id: String, consume: suspend (InputStream) -> R): AppResult<R> =
        remote.downloadFile(id, searchable = false, consume = consume)

    override suspend fun <R> downloadOriginal(
        id: String,
        pageId: String,
        consume: suspend (InputStream) -> R,
    ): AppResult<R> = remote.downloadPageOriginal(id, pageId, consume)

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
        pagesUpdatedAtIso = pagesUpdatedAt,
    )

    private fun DocumentPagesDto.toRemote(): RemotePageList =
        RemotePageList(pagesUpdatedAt, pages.sortedBy { it.position }.map { it.toRemote() })

    private fun DocumentPageDto.toRemote(): RemotePageInfo = RemotePageInfo(
        id = id,
        position = position,
        width = width,
        height = height,
        editJson = edit?.takeUnless { it is JsonNull }?.let { RecipeJson.encodeToString(JsonElement.serializer(), it) },
        originalSha256 = originalSha256.takeIf { hasOriginal },
        originalSizeBytes = originalSizeBytes.takeIf { hasOriginal },
        updatedAtIso = updatedAt,
    )

    /** A stored recipe is sent as the JSON object it is; one that does not parse is left out. */
    private fun parseRecipe(json: String): JsonElement? = try {
        RecipeJson.parseToJsonElement(json).takeUnless { it is JsonNull }
    } catch (e: SerializationException) {
        null
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val STATUS_READY = "READY"
        val RecipeJson = Json
    }
}
