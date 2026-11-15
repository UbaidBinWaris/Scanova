package app.scanova.core.data.sync

// Ports of the page backup (PagePusher) and page restore (PageRestorer): the
// same idea as SyncPorts.kt, so both run on the JVM against in-memory fakes.

/** A page as page sync sees it. */
internal data class SyncPage(
    val id: String,
    val documentId: String,
    val position: Int,
    /** Name of the encrypted current image in the file store. */
    val imageFile: String,
    val width: Int,
    val height: Int,
    val ocrText: String,
    /** The recipe as JSON (`PageEdit`), or null when unknown. */
    val editJson: String?,
    /** Name of the encrypted original, when this phone has it. */
    val originalFile: String?,
    /** SHA-256 (hex) and size of the original's plaintext. */
    val originalSha256: String?,
    val originalSizeBytes: Long?,
    /**
     * The server has this original. With [originalFile] null it means "on the
     * server, not downloaded to this phone yet".
     */
    val originalUploaded: Boolean,
)

/** A page as sent in `PUT documents/:id/pages`. */
internal data class OutgoingPage(
    val id: String,
    val position: Int,
    val width: Int,
    val height: Int,
    val editJson: String?,
)

/** A page as the server describes it. */
internal data class RemotePageInfo(
    val id: String,
    val position: Int,
    val width: Int,
    val height: Int,
    /** The recipe as JSON text, or null. */
    val editJson: String?,
    /** Set when the server stores the page's original. */
    val originalSha256: String?,
    val originalSizeBytes: Long?,
    val updatedAtIso: String,
)

internal data class RemotePageList(
    val pagesUpdatedAtIso: String?,
    /** In order. */
    val pages: List<RemotePageInfo>,
)

/** Pages rebuilt for a document, ready to replace what this phone has. */
internal data class PageRestore(
    val documentId: String,
    /** The PDF content the pages were made from; the restore is dropped if the document moved on meanwhile. */
    val contentSha256: String?,
    val pages: List<SyncPage>,
    /** A newly stored local PDF that becomes the document's file, or null to keep the current one. */
    val pdfFile: String?,
    /** The server's `pagesUpdatedAt` these pages match. */
    val remotePagesUpdatedAt: String?,
)

internal interface PageStore {
    /** Synced documents whose page list changed here and has not been sent yet. */
    suspend fun pagesToPush(): List<SyncDocument>

    /** The pages of a document, in order. */
    suspend fun pages(documentId: String): List<SyncPage>

    /** Changes one page's row. Returns false when the page is gone. */
    suspend fun updatePage(pageId: String, change: (SyncPage) -> SyncPage): Boolean

    /**
     * The page list and every original that could be sent are on the server:
     * clears pagesDirty (and pagesStale: the server now has exactly these pages)
     * and records the server's `pagesUpdatedAt`.
     */
    suspend fun pagesPushed(documentId: String, remotePagesUpdatedAt: String?)

    /** Synced documents whose pages here are missing or out of date, without local page changes. */
    suspend fun pagesToRestore(): List<SyncDocument>

    /**
     * Once per installation: documents that came from the server before page
     * restore existed (synced, no pages here) are queued for it.
     */
    suspend fun queueDocumentsWithoutPages()

    /**
     * Makes [restore] the document's pages and clears pagesStale, but only while
     * the document is still synced, without local page changes and at
     * [PageRestore.contentSha256]. Returns the files the document no longer uses
     * (to delete), or null when nothing was written.
     */
    suspend fun restorePages(restore: PageRestore): List<String>?

    /** Stops trying to restore this document's pages (until it changes on the server again). */
    suspend fun stopRestoring(documentId: String)

    /** Pages whose original is on the server but not on this phone. */
    suspend fun missingOriginals(): List<SyncPage>

    /**
     * Records a downloaded original, if the page still expects exactly that one
     * (no local original, same SHA-256). Returns false when nothing was changed.
     */
    suspend fun attachOriginal(pageId: String, sha256Hex: String, file: String): Boolean
}
