package app.scanova.feature.scan.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Small bitmap helpers with explicit memory limits: camera photos are large. */
internal object Bitmaps {

    /**
     * Decodes a JPEG so that its longer side is at most roughly [maxLongEdge]
     * (the decoder shrinks by powers of two, then an exact scale follows when
     * still too large). Returns null when the bytes are not a decodable image.
     */
    fun decodeSampled(jpeg: ByteArray, maxLongEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongEdge) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        return scaleDown(decoded, maxLongEdge)
    }

    /** Returns [bitmap] itself when it already fits, otherwise a smaller copy (and recycles the original). */
    fun scaleDown(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxLongEdge) return bitmap
        val factor = maxLongEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * factor).roundToInt()),
            max(1, (bitmap.height * factor).roundToInt()),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** A reduced copy for display; [bitmap] is left untouched. */
    fun thumbnail(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxLongEdge) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val factor = maxLongEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * factor).roundToInt()),
            max(1, (bitmap.height * factor).roundToInt()),
            true,
        )
    }

    /** Turns the photo upright. CameraX reports how far the sensor image has to be rotated clockwise. */
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun toJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream(bitmap.width * bitmap.height / 8)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }
}
