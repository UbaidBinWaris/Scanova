package app.scanova.core.data.storage

/** Names of encrypted files in the file store. An id is always a UUID, so names are safe. */
internal object FileNames {
    const val PAGES_DIR = "pages"
    const val PDF_DIR = "pdf"
    const val SCRATCH_DIR = "scratch"

    /** A finished page image of a document (cropped and filtered). */
    fun page(pageId: String) = "$PAGES_DIR/$pageId.jpg"

    fun pdf(documentId: String) = "$PDF_DIR/$documentId.pdf"

    /** The untouched camera capture of a page still being edited. */
    fun scratch(pageId: String) = "$SCRATCH_DIR/$pageId.jpg"
}
