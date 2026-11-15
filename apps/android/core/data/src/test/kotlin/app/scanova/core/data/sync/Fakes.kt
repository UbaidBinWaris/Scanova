package app.scanova.core.data.sync

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.sha256
import app.scanova.core.common.util.toHex
import app.scanova.core.data.model.SyncState
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal fun api(code: ApiErrorCode, status: Int): AppResult.Failure = AppResult.Failure(AppError.Api(code, status))

internal fun localDocument(
    id: String,
    state: SyncState = SyncState.LOCAL_ONLY,
    title: String = "Title $id",
    tags: List<String> = emptyList(),
    remoteCreated: Boolean = false,
    fileUploaded: Boolean = false,
    metadataDirty: Boolean = false,
    pdfFile: String? = "pdf/$id.pdf",
    ocrText: String = "recognised text of $id",
    contentSha256: String? = "ab".repeat(32),
    pagesDirty: Boolean = false,
    pagesStale: Boolean = false,
    remotePagesUpdatedAt: String? = null,
    hasSearchablePdf: Boolean = false,
) = SyncDocument(
    id = id,
    title = title,
    tags = tags,
    pageCount = 2,
    capturedAtIso = "2026-11-10T09:00:00Z",
    ocrText = ocrText,
    sizeBytes = 1234,
    contentSha256 = contentSha256,
    pdfFile = pdfFile,
    state = state,
    remoteCreated = remoteCreated,
    fileUploaded = fileUploaded,
    metadataDirty = metadataDirty,
    lastErrorCode = null,
    pagesDirty = pagesDirty,
    pagesStale = pagesStale,
    remotePagesUpdatedAt = remotePagesUpdatedAt,
    hasSearchablePdf = hasSearchablePdf,
)

internal fun remoteDocument(
    id: String,
    title: String = "Title $id",
    tags: List<String> = emptyList(),
    updatedAt: String = "2026-11-10T09:05:00.000Z",
    ready: Boolean = true,
    deleted: Boolean = false,
    ocrStatus: String = "PENDING",
    contentSha256: String? = "ab".repeat(32),
    pagesUpdatedAt: String? = null,
) = RemoteDocument(
    id = id,
    title = title,
    tags = tags,
    pageCount = 2,
    sizeBytes = 1234,
    contentSha256 = contentSha256,
    ready = ready,
    ocrStatus = ocrStatus,
    hasSearchablePdf = ocrStatus == "DONE",
    capturedAtIso = "2026-11-10T09:00:00.000Z",
    createdAtIso = "2026-11-10T09:00:01.000Z",
    updatedAtIso = updatedAt,
    deleted = deleted,
    pagesUpdatedAtIso = pagesUpdatedAt,
)

internal fun localPage(
    id: String,
    documentId: String,
    position: Int,
    editJson: String? = """{"v":1,"filter":"enhanced"}""",
    originalFile: String? = "originals/$id.jpg",
    originalSha256: String? = null,
    originalSizeBytes: Long? = null,
    originalUploaded: Boolean = false,
    imageFile: String = "pages/$id.jpg",
) = SyncPage(
    id = id,
    documentId = documentId,
    position = position,
    imageFile = imageFile,
    width = 1240,
    height = 1754,
    ocrText = "text of $id",
    editJson = editJson,
    originalFile = originalFile,
    originalSha256 = originalSha256,
    originalSizeBytes = originalSizeBytes,
    originalUploaded = originalUploaded,
)

/**
 * The smallest byte sequence [app.scanova.core.data.pdf.JpegInfo] accepts as a
 * JPEG of [width] × [height]: SOI, a baseline frame header and EOI.
 */
internal fun tinyJpeg(width: Int, height: Int, marker: Int = 0): ByteArray = unsignedBytes(
    0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08,
    height shr 8, height and 0xFF, width shr 8, width and 0xFF,
    0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
    0xFF, 0xFE, 0x00, 0x03, marker and 0xFF,
    0xFF, 0xD9,
).let { ints -> ByteArray(ints.size) { ints[it].toByte() } }

private fun unsignedBytes(vararg values: Int): IntArray = values

/** In-memory stand-in for the Room-backed store, using the real state machine and content rule. */
internal class FakeSyncStore(vararg initial: SyncDocument) : SyncStore, PageStore {
    val documents = initial.associateBy { it.id }.toMutableMap()
    val pages = mutableMapOf<String, SyncPage>()
    val attempts = mutableMapOf<String, Int>()
    val removed = mutableListOf<String>()
    var cursor: String? = null
    var backfilled = false

    fun state(id: String): SyncState? = documents[id]?.state

    fun pagesOf(documentId: String): List<SyncPage> =
        pages.values.filter { it.documentId == documentId }.sortedBy { it.position }

    fun addPages(vararg added: SyncPage) {
        added.forEach { pages[it.id] = it }
    }

    override suspend fun pendingPush() = documents.values.filter {
        it.state in setOf(SyncState.LOCAL_ONLY, SyncState.UPLOADING, SyncState.FAILED) ||
            (it.state == SyncState.SYNCED && it.metadataDirty)
    }

    override suspend fun pendingDelete() = documents.values.filter { it.state == SyncState.PENDING_DELETE }

    override suspend fun get(id: String) = documents[id]

    override suspend fun transition(id: String, event: SyncEvent, change: (SyncProgress) -> SyncProgress): Boolean {
        val current = documents[id] ?: return false
        val next = SyncStateMachine.next(current.state, event) ?: return false
        documents[id] = current.apply(change(current.progress())).copy(state = next)
        return true
    }

    override suspend fun updateProgress(id: String, change: (SyncProgress) -> SyncProgress): Boolean {
        val current = documents[id] ?: return false
        documents[id] = current.apply(change(current.progress()))
        return true
    }

    override suspend fun remove(id: String) {
        documents.remove(id)
        pages.values.removeAll { it.documentId == id }
        removed += id
    }

    override suspend fun applyRemote(remote: RemoteDocument) {
        val current = documents[remote.id]
        documents[remote.id] = if (current == null) {
            localDocument(
                id = remote.id,
                state = SyncState.SYNCED,
                title = remote.title,
                tags = remote.tags,
                remoteCreated = true,
                fileUploaded = true,
                pdfFile = null,
                ocrText = "",
                contentSha256 = remote.contentSha256,
                pagesStale = true,
                hasSearchablePdf = remote.hasSearchablePdf,
            )
        } else if (current.state != SyncState.SYNCED) {
            current
        } else {
            val change = remoteContentChange(
                current.contentSha256,
                current.pagesDirty,
                current.pagesStale,
                current.remotePagesUpdatedAt,
                remote,
            )
            current.copy(
                title = if (current.metadataDirty) current.title else remote.title,
                tags = if (current.metadataDirty) current.tags else remote.tags,
                contentSha256 = if (change.pdfReplaced) remote.contentSha256 else current.contentSha256,
                pdfFile = if (change.pdfReplaced) null else current.pdfFile,
                pagesDirty = change.pagesDirty,
                pagesStale = change.pagesStale,
                hasSearchablePdf = remote.hasSearchablePdf,
            )
        }
    }

    override suspend fun setOcrText(id: String, text: String) {
        documents[id]?.let { documents[id] = it.copy(ocrText = text) }
    }

    override suspend fun pullCursor() = cursor

    override suspend fun setPullCursor(cursor: String) {
        this.cursor = cursor
    }

    // ── Pages ───────────────────────────────────────────────────────────

    override suspend fun pagesToPush() =
        documents.values.filter { it.state == SyncState.SYNCED && it.pagesDirty }

    override suspend fun pages(documentId: String) = pagesOf(documentId)

    override suspend fun updatePage(pageId: String, change: (SyncPage) -> SyncPage): Boolean {
        val current = pages[pageId] ?: return false
        val changed = change(current)
        pages[pageId] = current.copy(
            originalSha256 = changed.originalSha256,
            originalSizeBytes = changed.originalSizeBytes,
            originalUploaded = changed.originalUploaded,
        )
        return true
    }

    override suspend fun pagesPushed(documentId: String, remotePagesUpdatedAt: String?) {
        val current = documents[documentId] ?: return
        documents[documentId] = current.copy(
            pagesDirty = false,
            pagesStale = false,
            remotePagesUpdatedAt = remotePagesUpdatedAt ?: current.remotePagesUpdatedAt,
        )
    }

    override suspend fun pagesToRestore() =
        documents.values.filter { it.state == SyncState.SYNCED && it.pagesStale && !it.pagesDirty }

    override suspend fun queueDocumentsWithoutPages() {
        if (backfilled) return
        backfilled = true
        documents.values
            .filter { it.state == SyncState.SYNCED && it.fileUploaded && !it.pagesDirty && pagesOf(it.id).isEmpty() }
            .forEach { documents[it.id] = it.copy(pagesStale = true) }
    }

    override suspend fun restorePages(restore: PageRestore): List<String>? {
        val current = documents[restore.documentId] ?: return null
        if (current.state != SyncState.SYNCED || current.pagesDirty || current.contentSha256 != restore.contentSha256) {
            return null
        }
        val previous = pagesOf(restore.documentId)
        pages.values.removeAll { it.documentId == restore.documentId }
        restore.pages.forEach { pages[it.id] = it }
        val replacedPdf = current.pdfFile.takeIf { restore.pdfFile != null && it != restore.pdfFile }
        documents[restore.documentId] = current.copy(
            pdfFile = restore.pdfFile ?: current.pdfFile,
            pagesStale = false,
            remotePagesUpdatedAt = restore.remotePagesUpdatedAt,
        )
        return previous.flatMap { listOfNotNull(it.imageFile, it.originalFile) } + listOfNotNull(replacedPdf)
    }

    override suspend fun stopRestoring(documentId: String) {
        documents[documentId]?.let { documents[documentId] = it.copy(pagesStale = false) }
    }

    override suspend fun missingOriginals() = pages.values
        .filter { page ->
            val document = documents[page.documentId]
            document != null && document.state == SyncState.SYNCED && !document.pagesDirty && !document.pagesStale &&
                page.originalFile == null && page.originalUploaded && page.originalSha256 != null
        }
        .sortedBy { it.position }

    override suspend fun attachOriginal(pageId: String, sha256Hex: String, file: String): Boolean {
        val current = pages[pageId] ?: return false
        if (current.originalFile != null || current.originalSha256 != sha256Hex) return false
        pages[pageId] = current.copy(originalFile = file, originalUploaded = true)
        return true
    }

    private fun SyncDocument.progress() = SyncProgress(
        title = title,
        tags = tags,
        remoteCreated = remoteCreated,
        fileUploaded = fileUploaded,
        metadataDirty = metadataDirty,
        lastErrorCode = lastErrorCode,
        syncAttempts = attempts[id] ?: 0,
        ocrStatus = "NONE",
        hasSearchablePdf = false,
    )

    private fun SyncDocument.apply(progress: SyncProgress): SyncDocument {
        attempts[id] = progress.syncAttempts
        return copy(
            remoteCreated = progress.remoteCreated,
            fileUploaded = progress.fileUploaded,
            metadataDirty = progress.metadataDirty,
            lastErrorCode = progress.lastErrorCode,
        )
    }
}

/** Scriptable server. Every call is recorded as a line in [calls]. */
internal class FakeSyncApi : SyncApi {
    val calls = mutableListOf<String>()
    val createdOcrText = mutableMapOf<String, String?>()
    val patched = mutableMapOf<String, Pair<String, List<String>>>()
    val uploadedBytes = mutableMapOf<String, ByteArray>()

    var createResult: (SyncDocument) -> AppResult<RemoteDocument> = { AppResult.Success(remoteDocument(it.id, it.title, it.tags, ready = false)) }
    var uploadResult: (String) -> AppResult<RemoteDocument> = { AppResult.Success(remoteDocument(it)) }
    var patchResult: (String) -> AppResult<RemoteDocument> = { AppResult.Success(remoteDocument(it)) }
    var deleteResult: (String) -> AppResult<Unit> = { AppResult.Success(Unit) }
    var listPages: MutableList<AppResult<RemotePage>> = mutableListOf(AppResult.Success(RemotePage(emptyList(), null)))
    var textResult: (String) -> AppResult<String> = { AppResult.Success("") }

    /** Runs right after the server accepted a create, to simulate things happening meanwhile. */
    var afterCreate: suspend (String) -> Unit = {}

    // ── Pages: a small in-memory page store with server timestamps ──────

    /** False: a server from before page backup, which answers every page route with 404. */
    var pagesSupported = true

    /** The page lists the server holds, by document. */
    val serverPages = mutableMapOf<String, RemotePageList>()

    /** Originals the server holds, by "document/page". */
    val serverOriginals = mutableMapOf<String, ByteArray>()

    /** PDFs the server holds (`variant=original`), by document. */
    val serverPdfs = mutableMapOf<String, ByteArray>()

    /** Overrides for single calls; null means the in-memory server answers. */
    var replacePagesResult: ((String) -> AppResult<RemotePageList>?) = { null }
    var uploadOriginalResult: ((String, String) -> AppResult<RemotePageInfo>?) = { _, _ -> null }
    var pagesResult: ((String) -> AppResult<RemotePageList>?) = { null }
    var downloadPdfResult: ((String) -> AppResult<Unit>?) = { null }
    var downloadOriginalResult: ((String, String) -> AppResult<Unit>?) = { _, _ -> null }

    /** What each original upload declared and sent. */
    val sentOriginals = mutableMapOf<String, Triple<String, Long, ByteArray>>()

    private var clock = 0

    private fun tick(): String = "2026-11-16T10:%02d:%02d.000Z".format(clock / 60, clock++ % 60)

    override suspend fun create(document: SyncDocument, clientOcrText: String?): AppResult<RemoteDocument> {
        calls += "create ${document.id}"
        createdOcrText[document.id] = clientOcrText
        val result = createResult(document)
        if (result is AppResult.Success) afterCreate(document.id)
        return result
    }

    override suspend fun upload(id: String, sha256Hex: String, length: Long, open: () -> InputStream): AppResult<RemoteDocument> {
        calls += "upload $id"
        val result = uploadResult(id)
        if (result is AppResult.Success) uploadedBytes[id] = open().use { it.readBytes() }
        return result
    }

    override suspend fun updateMetadata(id: String, title: String, tags: List<String>): AppResult<RemoteDocument> {
        calls += "patch $id"
        val result = patchResult(id)
        if (result is AppResult.Success) patched[id] = title to tags
        return result
    }

    override suspend fun delete(id: String): AppResult<Unit> {
        calls += "delete $id"
        return deleteResult(id)
    }

    override suspend fun list(cursor: String?, updatedSince: String?): AppResult<RemotePage> {
        calls += "list cursor=$cursor since=$updatedSince"
        return if (listPages.size > 1) listPages.removeAt(0) else listPages.first()
    }

    override suspend fun text(id: String): AppResult<String> {
        calls += "text $id"
        return textResult(id)
    }

    override suspend fun replacePages(id: String, pages: List<OutgoingPage>): AppResult<RemotePageList> {
        calls += "put pages $id"
        replacePagesResult(id)?.let { return it }
        if (!pagesSupported) return api(ApiErrorCode.NOT_FOUND, 404)
        val before = serverPages[id]?.pages.orEmpty().associateBy { it.id }
        val now = tick()
        val list = RemotePageList(
            now,
            pages.sortedBy { it.position }.mapIndexed { index, page ->
                val kept = before[page.id]
                RemotePageInfo(page.id, index, page.width, page.height, page.editJson, kept?.originalSha256, kept?.originalSizeBytes, now)
            },
        )
        serverPages[id] = list
        return AppResult.Success(list)
    }

    override suspend fun uploadOriginal(
        id: String,
        pageId: String,
        sha256Hex: String,
        length: Long,
        open: () -> InputStream,
    ): AppResult<RemotePageInfo> {
        calls += "put original $id/$pageId"
        uploadOriginalResult(id, pageId)?.let { return it }
        if (!pagesSupported) return api(ApiErrorCode.NOT_FOUND, 404)
        val list = serverPages[id] ?: return api(ApiErrorCode.NOT_FOUND, 404)
        val page = list.pages.firstOrNull { it.id == pageId } ?: return api(ApiErrorCode.NOT_FOUND, 404)
        val bytes = open().use { it.readBytes() }
        sentOriginals[pageId] = Triple(sha256Hex, length, bytes)
        if (sha256(bytes).toHex() != sha256Hex || bytes.size.toLong() != length) {
            return api(ApiErrorCode.CHECKSUM_MISMATCH, 422)
        }
        serverOriginals["$id/$pageId"] = bytes
        val now = tick()
        val updated = page.copy(originalSha256 = sha256Hex, originalSizeBytes = length, updatedAtIso = now)
        serverPages[id] = RemotePageList(now, list.pages.map { if (it.id == pageId) updated else it })
        return AppResult.Success(updated)
    }

    override suspend fun pages(id: String): AppResult<RemotePageList> {
        calls += "get pages $id"
        pagesResult(id)?.let { return it }
        if (!pagesSupported) return api(ApiErrorCode.NOT_FOUND, 404)
        return AppResult.Success(serverPages[id] ?: RemotePageList(null, emptyList()))
    }

    override suspend fun <R> downloadPdf(id: String, consume: suspend (InputStream) -> R): AppResult<R> {
        calls += "get pdf $id"
        downloadPdfResult(id)?.let { failure -> return failure as AppResult.Failure }
        val bytes = serverPdfs[id] ?: return api(ApiErrorCode.NOT_FOUND, 404)
        return AppResult.Success(consume(bytes.inputStream()))
    }

    override suspend fun <R> downloadOriginal(
        id: String,
        pageId: String,
        consume: suspend (InputStream) -> R,
    ): AppResult<R> {
        calls += "get original $id/$pageId"
        downloadOriginalResult(id, pageId)?.let { failure -> return failure as AppResult.Failure }
        val bytes = serverOriginals["$id/$pageId"] ?: return api(ApiErrorCode.NOT_FOUND, 404)
        return AppResult.Success(consume(bytes.inputStream()))
    }
}

/** An in-memory file store. Names given to the constructor exist with a recognisable content. */
internal class FakeSyncFiles(vararg present: String) : SyncFiles {
    val contents = present.associateWith { "%PDF-1.7 content of $it".toByteArray() }.toMutableMap()
    val deleted = mutableListOf<String>()

    fun put(name: String, bytes: ByteArray) {
        contents[name] = bytes
    }

    override fun exists(name: String) = name in contents

    override fun open(name: String): InputStream =
        contents[name]?.inputStream() ?: throw java.io.FileNotFoundException(name)

    override fun <T> write(name: String, block: (OutputStream) -> T): T {
        val out = ByteArrayOutputStream()
        val result = block(out)
        contents[name] = out.toByteArray()
        return result
    }

    override fun delete(name: String) {
        if (contents.remove(name) != null) deleted += name
    }
}
