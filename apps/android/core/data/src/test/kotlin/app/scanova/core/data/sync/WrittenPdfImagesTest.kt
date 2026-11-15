package app.scanova.core.data.sync

import app.scanova.core.data.pdf.PdfWriter
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrittenPdfImagesTest {

    private fun jpeg(width: Int, height: Int, color: Color): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun written(vararg pages: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = PdfWriter(out, Instant.parse("2026-11-16T10:00:00Z"), "Lease")
        pages.forEach(writer::addJpegPage)
        writer.finish()
        return out.toByteArray()
    }

    private fun extract(pdf: ByteArray): Pair<Int?, List<ByteArray>> {
        val pages = mutableListOf<ByteArray>()
        val count = WrittenPdfImages.pages({ pdf.inputStream() }) { index, jpeg ->
            assertEquals(pages.size, index)
            pages += jpeg
        }
        return count to pages
    }

    @Test
    fun `gives back every page image of the app's own PDF, byte for byte and in order`() {
        val images = listOf(
            jpeg(300, 400, Color.RED),
            jpeg(640, 480, Color.GREEN),
            jpeg(1240, 1754, Color.BLUE),
        )

        val (count, pages) = extract(written(*images.toTypedArray()))

        assertEquals(3, count)
        assertEquals(3, pages.size)
        images.zip(pages).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
    }

    @Test
    fun `refuses a PDF written by something else`() {
        val foreign = ByteArrayOutputStream().also { out ->
            PDDocument().use { document ->
                val page = PDPage()
                document.addPage(page)
                val image = JPEGFactory.createFromByteArray(document, jpeg(200, 300, Color.ORANGE))
                PDPageContentStream(document, page).use { it.drawImage(image, 0f, 0f) }
                document.save(out)
            }
        }.toByteArray()

        assertNull(extract(foreign).first)
        assertNull(extract("not a pdf at all".toByteArray()).first)
        assertNull(extract(ByteArray(0)).first)
    }

    @Test
    fun `refuses a truncated or tampered file`() {
        val pdf = written(jpeg(300, 400, Color.RED), jpeg(300, 400, Color.BLUE))

        // Cut before the page tree: the end of the file is missing.
        assertNull(extract(pdf.copyOf(pdf.size / 2)).first)
        // A page tree that claims another number of pages.
        val text = String(pdf, Charsets.ISO_8859_1).replace("/Type /Pages /Count 2", "/Type /Pages /Count 3")
        assertNull(extract(text.toByteArray(Charsets.ISO_8859_1)).first)
    }
}
