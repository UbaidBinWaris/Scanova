package app.scanova.feature.scan.pdf

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfWriterTest {

    private val createdAt = Instant.parse("2026-11-15T09:30:00Z")

    /** A real JPEG made by the JDK's encoder: a solid colour with a marker square. */
    private fun jpeg(width: Int, height: Int, color: Color = Color(40, 90, 200), grey: Boolean = false): ByteArray {
        val type = if (grey) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.WHITE
        graphics.fillRect(width / 4, height / 4, width / 2, height / 2)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun pdf(title: String? = "Lease", vararg pages: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = PdfWriter(out, createdAt, title)
        pages.forEach(writer::addJpegPage)
        writer.finish()
        return out.toByteArray()
    }

    // ── JPEG header ─────────────────────────────────────────────────────

    @Test
    fun `reads size and channels from colour and greyscale JPEGs`() {
        assertEquals(JpegInfo(300, 400, 3), JpegInfo.read(jpeg(300, 400)))
        assertEquals(JpegInfo(640, 480, 1), JpegInfo.read(jpeg(640, 480, grey = true)))
    }

    @Test
    fun `rejects data that is not a complete JPEG header`() {
        assertNull(JpegInfo.read(ByteArray(0)))
        assertNull(JpegInfo.read("%PDF-1.7".toByteArray()))
        assertNull(JpegInfo.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
        assertNull(JpegInfo.read(jpeg(300, 400).copyOf(20)))
        // PNG signature.
        assertNull(JpegInfo.read(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
    }

    // ── File structure ──────────────────────────────────────────────────

    @Test
    fun `output starts with the PDF signature the server checks for`() {
        val bytes = pdf("Lease", jpeg(300, 400))

        assertEquals("%PDF-", bytes.copyOf(5).toString(Charsets.US_ASCII))
        assertTrue(bytes.toString(Charsets.ISO_8859_1).trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun `cross-reference table points at every object`() {
        val bytes = pdf("Lease", jpeg(300, 400), jpeg(400, 300))
        val text = bytes.toString(Charsets.ISO_8859_1)

        val startXref = Regex("startxref\\n(\\d+)\\n%%EOF").find(text)!!.groupValues[1].toInt()
        assertEquals("xref", text.substring(startXref, startXref + 4))

        val header = Regex("xref\\n0 (\\d+)\\n").find(text, startXref)!!
        val count = header.groupValues[1].toInt()
        // Entries are fixed-width: 20 bytes each, ending in CR LF.
        val table = text.substring(header.range.last + 1)
        val entries = List(count) { table.substring(it * 20, it * 20 + 20) }
        assertEquals("0000000000 65535 f\r\n", entries[0])
        for (number in 1 until count) {
            assertTrue(entries[number].endsWith(" 00000 n\r\n"))
            val offset = entries[number].substring(0, 10).toInt()
            assertTrue("object $number", text.startsWith("$number 0 obj\n", offset))
        }
        assertTrue(table.substring(count * 20).startsWith("trailer\n"))
        // 3 fixed objects + 3 per page + the free entry.
        assertEquals(1 + 3 + 2 * 3, count)
    }

    // ── Validity, checked by an independent PDF implementation ──────────

    @Test
    fun `a real PDF parser opens the document and finds every page`() {
        val bytes = pdf("Lease", jpeg(300, 400), jpeg(400, 300), jpeg(200, 200, grey = true))

        Loader.loadPDF(bytes).use { document ->
            assertEquals(3, document.numberOfPages)
            assertEquals("Scanova", document.documentInformation.producer)
            assertEquals("Lease", document.documentInformation.title)
            assertEquals(createdAt, document.documentInformation.creationDate.toInstant())

            // Page 1: portrait, short side as wide as A4.
            val first = document.getPage(0).mediaBox
            assertEquals(595.28f, first.width, 0.01f)
            assertEquals(595.28f * 400 / 300, first.height, 0.01f)
            // Page 2: landscape.
            val second = document.getPage(1).mediaBox
            assertEquals(595.28f, second.height, 0.01f)
            assertEquals(595.28f * 400 / 300, second.width, 0.01f)
        }
    }

    @Test
    fun `the embedded image is the original JPEG, byte for byte`() {
        val page = jpeg(300, 400)
        val bytes = pdf("Lease", page)

        Loader.loadPDF(bytes).use { document ->
            val resources = document.getPage(0).resources
            val image = resources.getXObject(resources.xObjectNames.single()) as PDImageXObject
            assertEquals(300, image.width)
            assertEquals(400, image.height)
            assertEquals("DeviceRGB", image.colorSpace.name)
            // Not re-encoded: the stream holds exactly the bytes that were passed in.
            assertArrayEquals(page, image.cosObject.createRawInputStream().use { it.readBytes() })
        }
    }

    @Test
    fun `greyscale pages are declared as greyscale`() {
        Loader.loadPDF(pdf("G", jpeg(200, 200, grey = true))).use { document ->
            val resources = document.getPage(0).resources
            val image = resources.getXObject(resources.xObjectNames.single()) as PDImageXObject
            assertEquals("DeviceGray", image.colorSpace.name)
        }
    }

    @Test
    fun `the page renders with the image covering it edge to edge`() {
        val bytes = pdf("Lease", jpeg(300, 400, Color(40, 90, 200)))

        Loader.loadPDF(bytes).use { document ->
            val rendered = PDFRenderer(document).renderImage(0, 0.5f)
            // Corners show the page colour (not white paper), the middle shows the white marker.
            val corner = Color(rendered.getRGB(3, 3))
            val oppositeCorner = Color(rendered.getRGB(rendered.width - 4, rendered.height - 4))
            val center = Color(rendered.getRGB(rendered.width / 2, rendered.height / 2))
            assertTrue("corner $corner", corner.blue > 150 && corner.red < 100)
            assertTrue("corner $oppositeCorner", oppositeCorner.blue > 150 && oppositeCorner.red < 100)
            assertTrue("center $center", center.red > 230 && center.green > 230 && center.blue > 230)
        }
    }

    @Test
    fun `titles in any script survive`() {
        for (title in listOf("کرایہ نامہ 2026", "Straße (Zürich) \\ 100%", "領収書")) {
            Loader.loadPDF(pdf(title, jpeg(100, 100))).use { document ->
                assertEquals(title, document.documentInformation.title)
            }
        }
    }

    @Test
    fun `a missing title is simply absent`() {
        Loader.loadPDF(pdf(null, jpeg(100, 100))).use { document ->
            assertNull(document.documentInformation.title)
        }
    }

    @Test
    fun `many pages stay valid`() {
        val page = jpeg(120, 160)

        Loader.loadPDF(pdf("Big", *Array(60) { page })).use { document ->
            assertEquals(60, document.numberOfPages)
        }
    }

    // ── Misuse ──────────────────────────────────────────────────────────

    @Test
    fun `a page that is not a JPEG is refused`() {
        val writer = PdfWriter(ByteArrayOutputStream(), createdAt)

        assertThrows(IllegalArgumentException::class.java) { writer.addJpegPage("not an image".toByteArray()) }
    }

    @Test
    fun `an empty document cannot be finished and a finished one cannot be extended`() {
        assertThrows(IllegalStateException::class.java) { PdfWriter(ByteArrayOutputStream(), createdAt).finish() }

        val writer = PdfWriter(ByteArrayOutputStream(), createdAt)
        writer.addJpegPage(jpeg(100, 100))
        writer.finish()
        assertThrows(IllegalStateException::class.java) { writer.addJpegPage(jpeg(100, 100)) }
        assertThrows(IllegalStateException::class.java) { writer.finish() }
    }
}
