package app.scanova.core.data.sync

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.SyncState
import java.io.InputStream

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
) = SyncDocument(
    id = id,
    title = title,
    tags = tags,
    pageCount = 2,
    capturedAtIso = "2026-11-10T09:00:00Z",
    ocrText = ocrText,
    sizeBytes = 1234,
    contentSha256 = "ab".repeat(32),
    pdfFile = pdfFile,
    state = state,
    remoteCreated = remoteCreated,
    fileUploaded = fileUploaded,
    metadataDirty = metadataDirty,
    lastErrorCode = null,
)

internal fun remoteDocument(
    id: String,
    title: String = "Title $id",
    tags: List<String> = emptyList(),
    updatedAt: String = "2026-11-10T09:05:00.000Z",
    ready: Boolean = true,
    deleted: Boolean = false,
    ocrStatus: String = "PENDING",
) = RemoteDocument(
    id = id,
    title = title,
    tags = tags,
    pageCount = 2,
    sizeBytes = 1234,
    contentSha256 = "ab".repeat(32),
    ready = ready,
    ocrStatus = ocrStatus,
    hasSearchablePdf = ocrStatus == "DONE",
    capturedAtIso = "2026-11-10T09:00:00.000Z",
    createdAtIso = "2026-11-10T09:00:01.000Z",
    updatedAtIso = updatedAt,
    deleted = deleted,
)

/** In-memory stand-in for the Room-backed store, using the real state machine. */
internal class FakeSyncStore(vararg initial: SyncDocument) : SyncStore {
    val documents = initial.associateBy { it.id }.toMutableMap()
    val attempts = mutableMapOf<String, Int>()
    val removed = mutableListOf<String>()
    var cursor: String? = null

    fun state(id: String): SyncState? = documents[id]?.state

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
            )
        } else if (current.state != SyncState.SYNCED || current.metadataDirty) {
            current
        } else {
            current.copy(title = remote.title, tags = remote.tags)
        }
    }

    override suspend fun setOcrText(id: String, text: String) {
        documents[id]?.let { documents[id] = it.copy(ocrText = text) }
    }

    override suspend fun pullCursor() = cursor

    override suspend fun setPullCursor(cursor: String) {
        this.cursor = cursor
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
}

internal class FakeSyncFiles(vararg present: String) : SyncFiles {
    private val names = present.toMutableSet()

    override fun exists(name: String) = name in names

    override fun open(name: String): InputStream = "%PDF-1.7 content of $name".byteInputStream()
}
