package app.scanova.core.data.sync

import app.scanova.core.common.result.AppResult
import java.io.InputStream
import java.io.OutputStream

// The sync engine talks to the database, the server and the file store through
// these small interfaces. That keeps the engine free of Android and lets its
// decisions be tested on the JVM with in-memory fakes. Pages have their own
// ports in PagePorts.kt.

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
    /** The page list (order, recipes, originals) changed here and has not been sent. */
    val pagesDirty: Boolean = false,
    /** The pages here are missing or older than the server's and have to be rebuilt. */
    val pagesStale: Boolean = false,
    /** The server's `pagesUpdatedAt` the pages here match, when known. */
    val remotePagesUpdatedAt: String? = null,
    val hasSearchablePdf: Boolean = false,
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
    /** Null on servers without page backup, and for documents that never had a page list there. */
    val pagesUpdatedAtIso: String? = null,
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

    /**
     * Stores what the server says about a document this phone has fully synced
     * or has never seen. A new document, or one whose content or pages changed
     * on the server, is marked for a page restore (see [remoteContentChange]).
     */
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

    /** `PUT documents/:id/pages`. A server without page backup answers NOT_FOUND. */
    suspend fun replacePages(id: String, pages: List<OutgoingPage>): AppResult<RemotePageList>

    /** `PUT documents/:id/pages/:pageId/original`, streamed from [open]. */
    suspend fun uploadOriginal(
        id: String,
        pageId: String,
        sha256Hex: String,
        length: Long,
        open: () -> InputStream,
    ): AppResult<RemotePageInfo>

    /** `GET documents/:id/pages`. A server without page backup answers NOT_FOUND. */
    suspend fun pages(id: String): AppResult<RemotePageList>

    /** The PDF exactly as the phone that made it uploaded it (`variant=original`). */
    suspend fun <R> downloadPdf(id: String, consume: suspend (InputStream) -> R): AppResult<R>

    suspend fun <R> downloadOriginal(id: String, pageId: String, consume: suspend (InputStream) -> R): AppResult<R>
}

/** The phone's encrypted file store, as far as sync needs it. Blocking: call on an I/O thread. */
internal interface SyncFiles {
    fun exists(name: String): Boolean

    /** Opens the decrypted content of an encrypted file. */
    fun open(name: String): InputStream

    /** Writes [name] encrypted from what [block] writes; the file appears only when [block] succeeds. */
    fun <T> write(name: String, block: (OutputStream) -> T): T

    fun delete(name: String)
}
