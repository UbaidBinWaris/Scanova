package app.scanova.core.data.sync

/** What a pulled server document means for the content of a fully synced local copy. */
internal data class ContentChange(
    /** The server has a different PDF: the local PDF is out of date and is dropped. */
    val pdfReplaced: Boolean,
    val pagesStale: Boolean,
    val pagesDirty: Boolean,
)

/**
 * Decides how a synced document follows the server (one rule for every store):
 *
 * - A different PDF on the server (another device edited the pages) wins: the
 *   local PDF is dropped and the pages are rebuilt from the server's. A page
 *   list waiting to be sent from here described the old content and is dropped
 *   too.
 * - Otherwise a page list waiting to be sent wins until it has been sent.
 * - Otherwise a different `pagesUpdatedAt` (recipes or originals changed
 *   elsewhere) makes the pages stale.
 */
internal fun remoteContentChange(
    localSha256: String?,
    pagesDirty: Boolean,
    pagesStale: Boolean,
    localPagesUpdatedAt: String?,
    remote: RemoteDocument,
): ContentChange {
    val pdfReplaced = remote.contentSha256 != null && remote.contentSha256 != localSha256
    val pagesChanged = remote.pagesUpdatedAtIso != null && remote.pagesUpdatedAtIso != localPagesUpdatedAt
    return when {
        pdfReplaced -> ContentChange(pdfReplaced = true, pagesStale = true, pagesDirty = false)
        pagesDirty -> ContentChange(pdfReplaced = false, pagesStale = pagesStale, pagesDirty = true)
        pagesChanged -> ContentChange(pdfReplaced = false, pagesStale = true, pagesDirty = false)
        else -> ContentChange(pdfReplaced = false, pagesStale = pagesStale, pagesDirty = false)
    }
}
