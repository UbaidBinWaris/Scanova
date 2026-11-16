package app.scanova.feature.scan.importer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.core.graphics.createBitmap
import app.scanova.core.data.image.ExifTransform
import app.scanova.core.data.image.ImportedOriginal
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.render.RecipeGeometry
import app.scanova.feature.scan.session.PageFinisher
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A picked photo ready to become a page of the scan, exactly like a camera
 * capture: its original and an upright working copy to detect the page in
 * and render from.
 */
internal class PreparedPhoto(
    /** What is stored as the page's original: the picked file itself, or its upright re-encoding. */
    val original: ByteArray,
    /** Clockwise degrees that turn [original] upright (the page recipe's `sourceRotation`). */
    val sourceRotation: Int,
    /** Upright, at most [PageFinisher.CAPTURE_LONG_EDGE] on its longer side. The caller recycles it. */
    val upright: Bitmap,
)

/**
 * Turns the bytes of a picked photo into a [PreparedPhoto], decoding at
 * reduced size wherever possible (photos can be 50 MP and more).
 *
 * - A JPEG is kept byte for byte as the original; its EXIF orientation goes
 *   into the recipe as `sourceRotation`, as the camera's rotation does.
 * - A mirrored JPEG and any other format (PNG, WebP, HEIF, …) are re-encoded
 *   upright as JPEG quality [ImportedOriginal.REENCODE_QUALITY] at full
 *   resolution, up to [MAX_ORIGINAL_LONG_EDGE] (less when memory is short);
 *   transparency becomes white paper.
 *
 * Heavy work: call it on a background dispatcher. Returns null for data that
 * is not a picture; throws [OutOfMemoryError] when even the smallest attempt
 * does not fit.
 */
internal object PhotoPreparer {
    const val MAX_ORIGINAL_LONG_EDGE = 6000

    /** Working copy: like the camera's, and a softer one rather than none when memory is short. */
    private const val WORKING_LONG_EDGE = PageFinisher.CAPTURE_LONG_EDGE
    private const val MIN_WORKING_LONG_EDGE = 1200

    /** Sizes tried for a re-encoded original, largest first, when memory runs out. */
    private val ORIGINAL_EDGES = intArrayOf(MAX_ORIGINAL_LONG_EDGE, 4096, WORKING_LONG_EDGE)

    fun prepare(bytes: ByteArray): PreparedPhoto? = when (val plan = ImportedOriginal.of(bytes)) {
        is ImportedOriginal.KeepBytes -> {
            val decoded = Bitmaps.decodeWithin(bytes, WORKING_LONG_EDGE, MIN_WORKING_LONG_EDGE)
            decoded?.let {
                PreparedPhoto(
                    original = bytes,
                    sourceRotation = RecipeGeometry.normalizeDegrees(plan.sourceRotation),
                    upright = Bitmaps.rotate(it, plan.sourceRotation),
                )
            }
        }
        is ImportedOriginal.Reencode -> reencode(bytes, plan.transform)
    }

    private fun reencode(bytes: ByteArray, transform: ExifTransform): PreparedPhoto? {
        var shortOfMemory: OutOfMemoryError? = null
        for (edge in ORIGINAL_EDGES) {
            try {
                val decoded = decode(bytes, edge, platformOrientation = transform.isIdentity) ?: return null
                val upright = onWhite(applyTransform(decoded, transform))
                val original = try {
                    Bitmaps.toJpeg(upright, ImportedOriginal.REENCODE_QUALITY)
                } catch (e: Throwable) {
                    upright.recycle()
                    throw e
                }
                // scaleDown recycles the full-size bitmap when it makes a smaller one.
                return PreparedPhoto(original = original, sourceRotation = 0, upright = Bitmaps.scaleDown(upright, WORKING_LONG_EDGE))
            } catch (e: OutOfMemoryError) {
                shortOfMemory = e
            }
        }
        throw shortOfMemory ?: OutOfMemoryError()
    }

    /**
     * Decodes at most [maxLongEdge] on the longer side. With [platformOrientation]
     * the platform's decoder (Android 9+) also applies the file's own orientation
     * (HEIF, WebP, …); otherwise the pixels come as stored. Null when the data is
     * not a picture.
     */
    private fun decode(bytes: ByteArray, maxLongEdge: Int, platformOrientation: Boolean): Bitmap? {
        if (platformOrientation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longEdge = max(info.size.width, info.size.height)
                    if (longEdge > maxLongEdge) {
                        val scale = maxLongEdge.toDouble() / longEdge
                        decoder.setTargetSize(
                            max(1, (info.size.width * scale).roundToInt()),
                            max(1, (info.size.height * scale).roundToInt()),
                        )
                    }
                }
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        val bounds = Bitmaps.boundsOf(bytes) ?: return null
        var sample = 1
        while (max(bounds.width, bounds.height) / (sample * 2) >= maxLongEdge) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return Bitmaps.scaleDown(decoded, maxLongEdge)
    }

    /** Mirror, then turn clockwise; recycles [bitmap] when a new one is made. */
    private fun applyTransform(bitmap: Bitmap, transform: ExifTransform): Bitmap {
        if (transform.isIdentity) return bitmap
        val matrix = Matrix().apply {
            if (transform.mirrored) postScale(-1f, 1f)
            postRotate(transform.rotation.toFloat())
        }
        val turned = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    /** Pictures with transparency go on white paper (a JPEG has none; it would turn black). */
    private fun onWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val paper = try {
            createBitmap(bitmap.width, bitmap.height)
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        Canvas(paper).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return paper
    }
}
