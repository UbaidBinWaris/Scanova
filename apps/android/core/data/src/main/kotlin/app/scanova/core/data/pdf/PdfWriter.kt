package app.scanova.core.data.pdf

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes a PDF in which every page is one full-page JPEG.
 *
 * The JPEG bytes are embedded as they are (`/DCTDecode`), so a page costs
 * exactly the size of its image and is not recompressed. Android's own
 * `PdfDocument` stores bitmaps losslessly, which makes a photographed page many
 * megabytes; that is why this small writer exists.
 *
 * Everything is streamed to [output] as it is produced, and the only state kept
 * is the byte offset of each object for the cross-reference table. The caller
 * passes an encrypting stream, so the PDF never exists unencrypted on disk.
 *
 * Usage: [addJpegPage] for each page in order, then [finish] exactly once.
 */
class PdfWriter(
    private val output: OutputStream,
    private val createdAt: Instant,
    private val title: String? = null,
) {
    private var position = 0L
    private val offsets = mutableMapOf<Int, Long>()
    private val pageObjects = mutableListOf<Int>()
    private var nextObject = FIRST_FREE_OBJECT
    private var finished = false

    init {
        // The second line is a comment of four bytes above 127, the conventional
        // signal to transfer tools that the file is binary.
        write("%PDF-1.7\n")
        write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    /** Appends a page showing [jpeg] edge to edge. Throws if [jpeg] is not an embeddable JPEG. */
    fun addJpegPage(jpeg: ByteArray) {
        check(!finished) { "PDF already finished" }
        val info = JpegInfo.read(jpeg) ?: throw IllegalArgumentException("Page image is not a supported JPEG")
        val (pageWidth, pageHeight) = pageSize(info)

        val image = nextObject++
        val content = nextObject++
        val page = nextObject++

        val colorSpace = when (info.components) {
            1 -> "/DeviceGray"
            4 -> "/DeviceCMYK"
            else -> "/DeviceRGB"
        }
        // Adobe-style CMYK JPEGs store inverted values; the Decode array flips them back.
        val decode = if (info.components == 4) " /Decode [1 0 1 0 1 0 1 0]" else ""
        beginObject(image)
        write(
            "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode$decode /Length ${jpeg.size} >>\n" +
                "stream\n",
        )
        write(jpeg)
        write("\nendstream\nendobj\n")

        // Scale the unit square the image occupies up to the whole page.
        val drawing = "q ${number(pageWidth)} 0 0 ${number(pageHeight)} 0 0 cm /Im0 Do Q\n".toByteArray(Charsets.US_ASCII)
        beginObject(content)
        write("<< /Length ${drawing.size} >>\nstream\n")
        write(drawing)
        write("endstream\nendobj\n")

        beginObject(page)
        write(
            "<< /Type /Page /Parent $PAGES_OBJECT 0 R /MediaBox [0 0 ${number(pageWidth)} ${number(pageHeight)}] " +
                "/Resources << /XObject << /Im0 $image 0 R >> >> /Contents $content 0 R >>\nendobj\n",
        )
        pageObjects += page
    }

    /** Writes the document structure and the cross-reference table. Does not close [output]. */
    fun finish() {
        check(!finished) { "PDF already finished" }
        check(pageObjects.isNotEmpty()) { "A PDF needs at least one page" }
        finished = true

        beginObject(CATALOG_OBJECT)
        write("<< /Type /Catalog /Pages $PAGES_OBJECT 0 R >>\nendobj\n")

        beginObject(PAGES_OBJECT)
        write("<< /Type /Pages /Count ${pageObjects.size} /Kids [${pageObjects.joinToString(" ") { "$it 0 R" }}] >>\nendobj\n")

        beginObject(INFO_OBJECT)
        val titleEntry = title?.takeIf { it.isNotBlank() }?.let { " /Title ${textString(it)}" }.orEmpty()
        write("<< /Producer (Scanova) /CreationDate (${pdfDate(createdAt)})$titleEntry >>\nendobj\n")

        val objectCount = nextObject
        val xrefOffset = position
        val xref = StringBuilder("xref\n0 $objectCount\n")
        // Each entry is exactly 20 bytes: 10-digit offset, 5-digit generation, flag, CR LF.
        xref.append("0000000000 65535 f\r\n")
        for (number in 1 until objectCount) {
            val offset = offsets[number] ?: error("Object $number was never written")
            xref.append(String.format(Locale.ROOT, "%010d 00000 n\r\n", offset))
        }
        write(xref.toString())
        write("trailer\n<< /Size $objectCount /Root $CATALOG_OBJECT 0 R /Info $INFO_OBJECT 0 R >>\n")
        write("startxref\n$xrefOffset\n%%EOF\n")
        output.flush()
    }

    private fun beginObject(number: Int) {
        offsets[number] = position
        write("$number 0 obj\n")
    }

    private fun write(text: String) = write(text.toByteArray(Charsets.US_ASCII))

    private fun write(bytes: ByteArray) {
        output.write(bytes)
        position += bytes.size
    }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    /** PDF text string: UTF-16BE with a byte-order mark, written as a hex string so any script is safe. */
    private fun textString(text: String): String {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.take(MAX_TITLE_LENGTH).toByteArray(Charsets.UTF_16BE)
        return bytes.joinToString(separator = "", prefix = "<", postfix = ">") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
        }
    }

    private fun pdfDate(instant: Instant): String =
        "D:" + DATE_FORMAT.format(instant.atOffset(ZoneOffset.UTC)) + "Z"

    companion object {
        private const val CATALOG_OBJECT = 1
        private const val PAGES_OBJECT = 2
        private const val INFO_OBJECT = 3
        private const val FIRST_FREE_OBJECT = 4
        private const val MAX_TITLE_LENGTH = 200
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)

        /** Short side of A4 in PDF points (1/72 inch). */
        private const val A4_SHORT_SIDE = 595.28

        /**
         * The page takes the image's proportions, with its shorter side as wide
         * as A4. Printing then scales it to whatever paper is in the printer.
         */
        internal fun pageSize(info: JpegInfo): Pair<Double, Double> =
            if (info.width <= info.height) {
                A4_SHORT_SIDE to A4_SHORT_SIDE * info.height / info.width
            } else {
                A4_SHORT_SIDE * info.width / info.height to A4_SHORT_SIDE
            }
    }
}
