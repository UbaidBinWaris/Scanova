package app.scanova.core.data.storage

import java.util.UUID

/** Names of encrypted files in the file store. Ids are UUIDs, so names are safe. */
internal object FileNames {
    const val PAGES_DIR = "pages"
    const val ORIGINALS_DIR = "originals"
    const val PDF_DIR = "pdf"
    const val SCRATCH_DIR = "scratch"

    /** The current image of a page made by a new scan (cropped and filtered). */
    fun page(pageId: String) = "$PAGES_DIR/$pageId.jpg"

    /**
     * A new current image of a page that is being edited. The name is fresh
     * each time, so the image the document still uses stays untouched until
     * the edit is committed (a file's encryption is bound to its name, so files
     * are never renamed).
     */
    fun pageRevision(pageId: String) = "$PAGES_DIR/$pageId-${UUID.randomUUID()}.jpg"

    /** The untouched original photo of a page. */
    fun original(pageId: String) = "$ORIGINALS_DIR/$pageId.jpg"

    fun pdf(documentId: String) = "$PDF_DIR/$documentId.pdf"

    /** A rebuilt PDF of an edited document, written next to the one it replaces. */
    fun pdfRevision(documentId: String) = "$PDF_DIR/$documentId-${UUID.randomUUID()}.pdf"

    /** The working copy of a capture while a scan is in progress (upright, reduced size). */
    fun scratch(pageId: String) = "$SCRATCH_DIR/$pageId.jpg"
}
