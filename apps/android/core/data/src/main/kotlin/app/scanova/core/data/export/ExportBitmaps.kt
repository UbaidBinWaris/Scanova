package app.scanova.core.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max

/** Small bitmap helpers for exports and imports. Decoding is sampled, so a page never costs more memory than needed. */
object ExportBitmaps {

    /** Width × height of an encoded image without decoding it; null when it is not an image. */
    fun bounds(encoded: ByteArray): PixelSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) PixelSize(options.outWidth, options.outHeight) else null
    }

    /**
     * Decodes [encoded] at the smallest power-of-two reduction whose long edge
     * is still at least [minLongEdge] (the full image when it is smaller).
     */
    fun decodeAtLeast(encoded: ByteArray, minLongEdge: Int): Bitmap? {
        val size = bounds(encoded) ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(size.longEdge, minLongEdge) }
        return BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
    }

    /**
     * Decodes [encoded] at the smallest power-of-two reduction whose long edge
     * is at most [maxLongEdge] (full resolution when it already fits).
     */
    fun decodeWithin(encoded: ByteArray, maxLongEdge: Int): Bitmap? {
        val size = bounds(encoded) ?: return null
        var sample = 1
        while (size.longEdge / sample > max(1, maxLongEdge)) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
    }

    /** Largest power of two that keeps [longEdge] / sample ≥ [target]. */
    fun sampleSize(longEdge: Int, target: Int): Int {
        var sample = 1
        while (longEdge / (sample * 2) >= max(1, target)) sample *= 2
        return sample
    }

    /** [bitmap] scaled down so its long edge is at most [maxLongEdge]; recycles the input when it makes a new one. */
    fun fitWithin(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val target = PdfRenderSizing.fit(PixelSize(bitmap.width, bitmap.height), maxLongEdge)
        if (target.width == bitmap.width && target.height == bitmap.height) return bitmap
        val scaled = bitmap.scale(target.width, target.height, filter = true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun writeJpeg(bitmap: Bitmap, quality: Int, output: OutputStream) {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw IOException("jpeg_encode_failed")
    }

    fun toJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { writeJpeg(bitmap, quality, it) }.toByteArray()
}
