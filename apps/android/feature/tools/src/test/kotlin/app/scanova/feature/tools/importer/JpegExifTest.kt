package app.scanova.feature.tools.importer

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Test

class JpegExifTest {

    private val soi = bytes(0xFF, 0xD8)
    private val eoi = bytes(0xFF, 0xD9)

    /** An APP0 (JFIF) segment, as most encoders write before EXIF. */
    private val app0 = bytes(0xFF, 0xE0, 0x00, 0x10) + "JFIF".toByteArray() + bytes(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)

    /** A TIFF structure with one directory entry: Orientation (SHORT) = [orientation]. */
    private fun tiff(orientation: Int, littleEndian: Boolean, tag: Int = 0x0112, type: Int = 3): ByteArray {
        fun u16(value: Int) = if (littleEndian) bytes(value and 0xFF, value shr 8) else bytes(value shr 8, value and 0xFF)
        fun u32(value: Int) = if (littleEndian) u16(value and 0xFFFF) + u16(value ushr 16) else u16(value ushr 16) + u16(value and 0xFFFF)
        val order = if (littleEndian) "II".toByteArray() else "MM".toByteArray()
        return order + u16(42) + u32(8) +
            u16(1) + // one entry
            u16(tag) + u16(type) + u32(1) + u16(orientation) + u16(0) +
            u32(0) // no next directory
    }

    private fun app1(payload: ByteArray, header: String = "Exif"): ByteArray {
        val body = header.toByteArray() + bytes(0, 0) + payload
        val length = body.size + 2
        return bytes(0xFF, 0xE1, length shr 8, length and 0xFF) + body
    }

    @Test
    fun `reads the orientation in both byte orders`() {
        for (orientation in 1..8) {
            assertEquals(orientation, JpegExif.orientation(soi + app1(tiff(orientation, littleEndian = false)) + eoi))
            assertEquals(orientation, JpegExif.orientation(soi + app1(tiff(orientation, littleEndian = true)) + eoi))
        }
    }

    @Test
    fun `finds EXIF after other segments`() {
        val xmp = app1("<x:xmpmeta/>".toByteArray(), header = "http://ns.adobe.com/xap/1.0/")
        assertEquals(6, JpegExif.orientation(soi + app0 + xmp + app1(tiff(6, littleEndian = false)) + eoi))
    }

    @Test
    fun `a JPEG without EXIF is upright`() {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        assertEquals(ExifOrientation.NORMAL, JpegExif.orientation(jpeg))
    }

    @Test
    fun `damaged or odd data reads as upright`() {
        assertEquals(1, JpegExif.orientation(ByteArray(0)))
        assertEquals(1, JpegExif.orientation(bytes(0x89, 0x50, 0x4E, 0x47))) // PNG signature
        // Out-of-range value, wrong type, other tag, truncated segment.
        assertEquals(1, JpegExif.orientation(soi + app1(tiff(9, littleEndian = false)) + eoi))
        assertEquals(1, JpegExif.orientation(soi + app1(tiff(6, littleEndian = false, type = 4)) + eoi))
        assertEquals(1, JpegExif.orientation(soi + app1(tiff(6, littleEndian = false, tag = 0x0110)) + eoi))
        val full = soi + app1(tiff(6, littleEndian = true))
        assertEquals(1, JpegExif.orientation(full.copyOf(full.size - 6)))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
