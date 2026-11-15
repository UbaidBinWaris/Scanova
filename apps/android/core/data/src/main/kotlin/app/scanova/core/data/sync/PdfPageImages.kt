package app.scanova.core.data.sync

import app.scanova.core.data.pdf.JpegInfo
import java.io.InputStream

/** Turns a document's PDF into one JPEG per page (a page's *current* image). */
internal fun interface PdfPageImages {
    /**
     * Calls [onPage] with each page's JPEG, in order, and returns the number of
     * pages; or returns null when this PDF cannot be turned into page images
     * this way. [onPage] may already have been called for some pages when null
     * is returned, so the caller must be ready to throw those away.
     *
     * Blocking: call on an I/O thread.
     */
    fun pages(open: () -> InputStream, onPage: (index: Int, jpeg: ByteArray) -> Unit): Int?
}

/**
 * Reads the page images back out of a PDF written by
 * [app.scanova.core.data.pdf.PdfWriter], without decoding or re-encoding them:
 * that writer embeds every page as one JPEG, byte for byte, so the images
 * recovered here are exactly the current page images of the phone that made
 * the document. Every app version writes its PDFs this way, and the server
 * keeps that file unchanged (`variant=original`).
 *
 * The file is read once, front to back, keeping one page image in memory at a
 * time. Anything that does not look exactly like that writer's output (the
 * server's OCR'd copy, a PDF from elsewhere) is refused with null.
 */
internal object WrittenPdfImages : PdfPageImages {
    private const val IMAGE_DICTIONARY = "<< /Type /XObject /Subtype /Image "
    private const val STREAM_DICTIONARY = "<< /Length "
    private const val PAGES_DICTIONARY = "<< /Type /Pages /Count "
    private val LENGTH = Regex("""/Length (\d{1,10})""")
    private val COUNT = Regex("""^<< /Type /Pages /Count (\d{1,7})""")

    /** No phone camera produces a page image this large; a bigger number means a foreign or damaged file. */
    private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024

    override fun pages(open: () -> InputStream, onPage: (index: Int, jpeg: ByteArray) -> Unit): Int? =
        open().buffered(BUFFER_BYTES).use { input -> read(LineReader(input), onPage) }

    private fun read(reader: LineReader, onPage: (Int, ByteArray) -> Unit): Int? {
        if (reader.line()?.startsWith("%PDF-") != true) return null
        var count = 0
        while (true) {
            // The end of the file before the page tree: not a file this writer finished.
            val line = reader.line() ?: return null
            when {
                line.startsWith(IMAGE_DICTIONARY) -> {
                    if (!line.contains("/Filter /DCTDecode")) return null
                    val length = length(line)?.takeIf { it in 1..MAX_IMAGE_BYTES } ?: return null
                    if (reader.line() != "stream") return null
                    val jpeg = reader.bytes(length) ?: return null
                    if (JpegInfo.read(jpeg) == null) return null
                    onPage(count++, jpeg)
                }
                line.startsWith(STREAM_DICTIONARY) -> {
                    // A page's drawing instructions: skipped by length, never read as lines.
                    val length = length(line) ?: return null
                    if (reader.line() != "stream" || !reader.skip(length)) return null
                }
                line.startsWith(PAGES_DICTIONARY) -> {
                    val declared = COUNT.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                    return count.takeIf { it > 0 && it == declared }
                }
                // Object headers, page dictionaries, `endstream`, `endobj`, the binary comment: nothing to do.
                else -> Unit
            }
        }
    }

    private fun length(line: String): Int? = LENGTH.find(line)?.groupValues?.get(1)?.toLongOrNull()
        ?.takeIf { it <= Int.MAX_VALUE }?.toInt()

    /** Reads `\n`-terminated lines (cut to [MAX_LINE] characters) and exact byte runs. */
    private class LineReader(private val input: InputStream) {
        private val line = StringBuilder()

        fun line(): String? {
            line.setLength(0)
            while (true) {
                val next = input.read()
                if (next == -1) return if (line.isEmpty()) null else line.toString()
                if (next == '\n'.code) return line.toString()
                // Latin-1: one character per byte, so a binary line can never break decoding.
                if (line.length < MAX_LINE) line.append(next.toChar())
            }
        }

        fun bytes(count: Int): ByteArray? {
            val bytes = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(bytes, read, count - read)
                if (n <= 0) return null
                read += n
            }
            return bytes
        }

        fun skip(count: Int): Boolean {
            var left = count.toLong()
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() == -1) return false
                    left--
                } else {
                    left -= skipped
                }
            }
            return true
        }

        private companion object {
            const val MAX_LINE = 4096
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
