package app.scanova.core.data.image

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedOriginalTest {

    @Test
    fun `an upright or turned JPEG is kept byte for byte, its turn going into the recipe`() {
        assertEquals(ImportedOriginal.KeepBytes(0), ImportedOriginal.of(isJpeg = true, exifOrientation = ExifOrientation.NORMAL))
        assertEquals(ImportedOriginal.KeepBytes(90), ImportedOriginal.of(isJpeg = true, exifOrientation = ExifOrientation.ROTATE_90))
        assertEquals(ImportedOriginal.KeepBytes(180), ImportedOriginal.of(isJpeg = true, exifOrientation = ExifOrientation.ROTATE_180))
        assertEquals(ImportedOriginal.KeepBytes(270), ImportedOriginal.of(isJpeg = true, exifOrientation = ExifOrientation.ROTATE_270))
        // Missing or unknown orientation: as stored.
        assertEquals(ImportedOriginal.KeepBytes(0), ImportedOriginal.of(isJpeg = true, exifOrientation = 0))
    }

    @Test
    fun `a mirrored JPEG is re-encoded with the mirror baked in`() {
        for (orientation in listOf(
            ExifOrientation.FLIP_HORIZONTAL,
            ExifOrientation.FLIP_VERTICAL,
            ExifOrientation.TRANSPOSE,
            ExifOrientation.TRANSVERSE,
        )) {
            assertEquals(
                "orientation $orientation",
                ImportedOriginal.Reencode(ExifOrientation.transform(orientation)),
                ImportedOriginal.of(isJpeg = true, exifOrientation = orientation),
            )
        }
    }

    @Test
    fun `other formats are re-encoded as decoded`() {
        assertEquals(ImportedOriginal.Reencode(ExifTransform.NONE), ImportedOriginal.of(isJpeg = false, exifOrientation = 6))
    }

    @Test
    fun `decides from the file bytes`() {
        val image = BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

        assertEquals(ImportedOriginal.KeepBytes(0), ImportedOriginal.of(jpeg))
        assertEquals(ImportedOriginal.Reencode(ExifTransform.NONE), ImportedOriginal.of(png))
        assertEquals(ImportedOriginal.Reencode(ExifTransform.NONE), ImportedOriginal.of(ByteArray(0)))

        // The same JPEG with an EXIF segment saying "turn 90° clockwise" right after its start marker.
        val turned = jpeg.copyOfRange(0, 2) + exifSegment(ExifOrientation.ROTATE_90) + jpeg.copyOfRange(2, jpeg.size)
        assertEquals(ImportedOriginal.KeepBytes(90), ImportedOriginal.of(turned))
        val mirrored = jpeg.copyOfRange(0, 2) + exifSegment(ExifOrientation.FLIP_HORIZONTAL) + jpeg.copyOfRange(2, jpeg.size)
        assertEquals(ImportedOriginal.Reencode(ExifTransform(0, mirrored = true)), ImportedOriginal.of(mirrored))
    }

    /** An APP1 "Exif" segment with a big-endian TIFF directory holding only Orientation = [orientation]. */
    private fun exifSegment(orientation: Int): ByteArray {
        val tiff = bytes(
            'M'.code, 'M'.code, 0, 42, 0, 0, 0, 8, // header, first directory at 8
            0, 1, // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation, 0, 0, // Orientation, SHORT, count 1, value
            0, 0, 0, 0, // no next directory
        )
        val body = "Exif".toByteArray() + bytes(0, 0) + tiff
        val length = body.size + 2
        return bytes(0xFF, 0xE1, length shr 8, length and 0xFF) + body
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
