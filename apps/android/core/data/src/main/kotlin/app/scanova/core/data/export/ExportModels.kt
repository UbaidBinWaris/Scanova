package app.scanova.core.data.export

import java.io.File

/** How documents leave the app when they are shared or saved. */
enum class ExportFormat(val mimeType: String) {
    /** One PDF per document, exactly as stored. */
    PDF("application/pdf"),

    /** Every page as its own JPEG. */
    IMAGES("image/jpeg"),

    /** All pages of a document stitched under each other into one tall JPEG (several when very long). */
    LONG_IMAGE("image/jpeg"),

    /** The recognised text of each document as a UTF-8 `.txt` file. */
    TEXT("text/plain"),
}

/** Size and quality of exported images. */
enum class ImageQuality(
    /** JPEG quality of images that are encoded for the export. */
    val jpegQuality: Int,
    /** Longest edge of a page shared as its own image; null shares the stored page image as it is. */
    val pageLongEdge: Int?,
    /** Width of a long image (the pages are scaled to it). */
    val longImageWidth: Int,
    /** Resolution PDF pages are drawn at for a document that has no page images on this phone. */
    val renderDpi: Int,
) {
    HIGH(jpegQuality = 90, pageLongEdge = null, longImageWidth = 1600, renderDpi = 200),
    MEDIUM(jpegQuality = 72, pageLongEdge = 1600, longImageWidth = 1080, renderDpi = 150),
}

data class PixelSize(val width: Int, val height: Int) {
    val longEdge: Int get() = maxOf(width, height)
}

/** What one document offers to an export. */
data class ExportSource(
    val documentId: String,
    val title: String,
    val pageCount: Int,
    /**
     * Sizes of the page images stored on this phone, in order. Empty when the
     * document only has its PDF here (e.g. restored from backup): pages are
     * then drawn from the PDF, which needs Android 11 ([PdfPages.canOpenFromMemory]).
     */
    val pageSizes: List<PixelSize>,
    /** Size of the PDF, when known. */
    val pdfSizeBytes: Long?,
    val text: DocumentText,
) {
    val hasPageImages: Boolean get() = pageSizes.isNotEmpty()

    /** Page images can be made: stored here, or drawable from the PDF on this Android version. */
    val canExportImages: Boolean get() = hasPageImages || PdfPages.canOpenFromMemory
}

/** The finished files of one export, in a private directory of their own. */
data class ExportBundle(
    val directory: File,
    val files: List<File>,
    val mimeType: String,
)
