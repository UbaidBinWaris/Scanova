package app.scanova.feature.tools.importer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.core.graphics.createBitmap
import app.scanova.core.data.export.ExportBitmaps
import app.scanova.core.data.export.PixelSize
import app.scanova.core.data.pdf.JpegInfo
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** A picture ready to become a page: its original and its current (upright, reduced) image, both JPEG. */
internal class PreparedImage(
    val original: ByteArray,
    val current: ByteArray,
    /** Size of [current]. */
    val currentSize: PixelSize,
    /** Clockwise degrees that turn [original] upright; goes into the page's edit recipe. */
    val sourceRotation: Int,
)

/**
 * Turns an imported picture into a page the same way a scan does: the
 * original is kept untouched whenever possible, the current image is upright,
 * at most [CURRENT_LONG_EDGE] pixels and JPEG quality [CURRENT_QUALITY].
 *
 * - A JPEG is kept byte for byte as the original; its EXIF orientation goes
 *   into the recipe (`sourceRotation`) instead of rewriting its pixels.
 * - A mirrored JPEG (rare; recipes cannot mirror) and any other format (PNG,
 *   WebP, HEIF, …) are re-encoded upright as JPEG quality [ORIGINAL_QUALITY]
 *   at full resolution (capped at [MAX_ORIGINAL_LONG_EDGE]); transparency
 *   becomes white paper.
 *
 * Runs on a background thread; returns null for data that is not a picture.
 */
internal object ImportImages {
    const val CURRENT_LONG_EDGE = 2400
    const val CURRENT_QUALITY = 85
    const val ORIGINAL_QUALITY = 95
    const val MAX_ORIGINAL_LONG_EDGE = 6000

    fun prepare(bytes: ByteArray): PreparedImage? {
        if (JpegInfo.read(bytes) != null) {
            val transform = ExifOrientation.transform(JpegExif.orientation(bytes))
            if (!transform.mirrored) {
                val decoded = ExportBitmaps.decodeAtLeast(bytes, CURRENT_LONG_EDGE) ?: return null
                val current = upright(ExportBitmaps.fitWithin(decoded, CURRENT_LONG_EDGE), transform)
                return finish(original = bytes, current = current, sourceRotation = transform.rotation)
            }
            val decoded = ExportBitmaps.decodeWithin(bytes, MAX_ORIGINAL_LONG_EDGE) ?: return null
            return reencode(upright(decoded, transform))
        }
        val decoded = decodeOther(bytes) ?: return null
        return reencode(onWhite(decoded))
    }

    private fun reencode(upright: Bitmap): PreparedImage {
        val original = ExportBitmaps.toJpeg(upright, ORIGINAL_QUALITY)
        // fitWithin recycles the full-size bitmap when it makes a smaller one.
        return finish(original, ExportBitmaps.fitWithin(upright, CURRENT_LONG_EDGE), sourceRotation = 0)
    }

    private fun finish(original: ByteArray, current: Bitmap, sourceRotation: Int): PreparedImage = try {
        PreparedImage(
            original = original,
            current = ExportBitmaps.toJpeg(current, CURRENT_QUALITY),
            currentSize = PixelSize(current.width, current.height),
            sourceRotation = sourceRotation,
        )
    } finally {
        current.recycle()
    }

    /** Applies [transform]; recycles [bitmap] when a new one is made. */
    private fun upright(bitmap: Bitmap, transform: ExifTransform): Bitmap {
        if (transform.isIdentity) return bitmap
        val matrix = Matrix().apply {
            if (transform.mirrored) postScale(-1f, 1f)
            postRotate(transform.rotation.toFloat())
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    /** PNG, WebP, HEIF, GIF, … ImageDecoder (Android 9+) also applies their orientation. */
    private fun decodeOther(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return ExportBitmaps.decodeWithin(bytes, MAX_ORIGINAL_LONG_EDGE)
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longEdge = max(info.size.width, info.size.height)
                if (longEdge > MAX_ORIGINAL_LONG_EDGE) {
                    val scale = MAX_ORIGINAL_LONG_EDGE.toDouble() / longEdge
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).roundToInt()),
                        max(1, (info.size.height * scale).roundToInt()),
                    )
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    /** Pictures with transparency are put on white paper (JPEG has no transparency; it would turn black). */
    private fun onWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val paper = createBitmap(bitmap.width, bitmap.height)
        Canvas(paper).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return paper
    }
}
