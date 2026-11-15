package app.scanova.core.data.sync

import app.scanova.core.common.result.AppResult
import java.io.InputStream

// The sync engine talks to the database, the server and the file store through
// these small interfaces. That keeps the engine free of Android and lets its
// decisions be tested on the JVM with in-memory fakes.

/** A document as the sync engine needs to see it. */
internal data class SyncDocument(
    val id: String,
    val title: String,
    val tags: List<String>,
    val pageCount: Int,
    val capturedAtIso: String,
    val ocrText: String,
    val sizeBytes: Long?,
    val contentSha256: String?,
    val pdfFile: String?,
    val state: app.scanova.core.data.model.SyncState,
    val remoteCreated: Boolean,
    val fileUploaded: Boolean,
    val metadataDirty: Boolean,
    val lastErrorCode: String?,
)

/** A document as the server describes it. */
internal data class RemoteDocument(
    val id: String,
    val title: String,
    val tags: List<String>,
    val pageCount: Int,
    val sizeBytes: Long?,
    val contentSha256: String?,
    val ready: Boolean,
    val ocrStatus: String,
    val hasSearchablePdf: Boolean,
    val capturedAtIso: String,
    val createdAtIso: String,
    val updatedAtIso: String,
    val deleted: Boolean,
)

internal data class RemotePage(val documents: List<RemoteDocument>, val nextCursor: String?)

internal interface SyncStore {
    suspend fun pendingPush(): List<SyncDocument>

    suspend fun pendingDelete(): List<SyncDocument>

    suspend fun get(id: String): SyncDocument?

    /**
     * Applies [event] to the document through [SyncStateMachine] and, when the
     * transition is allowed, [change] to the rest of the row — atomically.
     * Returns false when the document is gone or the transition is not allowed.
     */
    suspend fun transition(id: String, event: SyncEvent, change: (SyncProgress) -> SyncProgress = { it }): Boolean

    /** Updates sync bookkeeping without changing the state. Returns false when the document is gone. */
    suspend fun updateProgress(id: String, change: (SyncProgress) -> SyncProgress): Boolean

    /** Removes the document, its pages and its files from this phone. */
    suspend fun remove(id: String)

    /** Stores what the server says about a document this phone has fully synced or has never seen. */
    suspend fun applyRemote(remote: RemoteDocument)

    suspend fun setOcrText(id: String, text: String)

    suspend fun pullCursor(): String?

    suspend fun setPullCursor(cursor: String)
}

/** The sync-related columns of a document, changed together with a state transition. */
internal data class SyncProgress(
    val title: String,
    val tags: List<String>,
    val remoteCreated: Boolean,
    val fileUploaded: Boolean,
    val metadataDirty: Boolean,
    val lastErrorCode: String?,
    val syncAttempts: Int,
    val ocrStatus: String,
    val hasSearchablePdf: Boolean,
)

internal interface SyncApi {
    suspend fun create(document: SyncDocument, clientOcrText: String?): AppResult<RemoteDocument>

    suspend fun upload(id: String, sha256Hex: String, length: Long, open: () -> InputStream): AppResult<RemoteDocument>

    suspend fun updateMetadata(id: String, title: String, tags: List<String>): AppResult<RemoteDocument>

    suspend fun delete(id: String): AppResult<Unit>

    suspend fun list(cursor: String?, updatedSince: String?): AppResult<RemotePage>

    suspend fun text(id: String): AppResult<String>
}

internal interface SyncFiles {
    fun exists(name: String): Boolean

    /** Opens the decrypted content of an encrypted file. */
    fun open(name: String): InputStream
}
