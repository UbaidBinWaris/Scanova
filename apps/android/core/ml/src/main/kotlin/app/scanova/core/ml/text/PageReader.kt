package app.scanova.core.ml.text

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What [PageReader] read on a page. */
data class PageReading(
    /** The page's text, read the right way up; empty when there is none. */
    val text: String,
    /** Clockwise quarter turn (0, 90, 180 or 270) that makes the page upright; 0 when it already is or when unsure. */
    val turn: Int,
    /** False when recognition itself failed (the text is then empty but not known to be absent). */
    val recognized: Boolean = true,
)

/**
 * Reads a page's text on the phone and, when asked, which way up the page is
 * ([PageOrientation]). One recognition pass for an upright page — the same
 * pass that reads its text — and a few more on a reduced copy only when that
 * pass is unsure.
 */
@Singleton
class PageReader @Inject constructor(private val engine: TextEngine) {

    /**
     * Reads [page] (left untouched). With [detectTurn] the page may come back
     * with a [PageReading.turn]; its text is then read turned that way.
     */
    suspend fun read(page: Bitmap, detectTurn: Boolean): PageReading {
        val first = engine.recognize(page, 0) ?: return PageReading("", 0, recognized = false)
        if (!detectTurn) return PageReading(first.text.take(MAX_CHARACTERS), 0)
        val firstQuality = PageOrientation.quality(first)
        if (firstQuality.confidentlyUpright || firstQuality.totalChars == 0) {
            return PageReading(first.text.take(MAX_CHARACTERS), 0)
        }
        val probe = reduced(page)
        val turn = try {
            PageOrientation.search(firstQuality) { candidate ->
                engine.recognize(probe, candidate)?.let(PageOrientation::quality)
            }
        } finally {
            if (probe !== page) probe.recycle()
        }
        if (turn == 0) return PageReading(first.text.take(MAX_CHARACTERS), 0)
        // The text for search and titles comes from the full-size page, read the right way up.
        val turned = engine.recognize(page, turn) ?: return PageReading(first.text.take(MAX_CHARACTERS), 0)
        return PageReading(turned.text.take(MAX_CHARACTERS), turn)
    }

    /**
     * Decodes a stored page image (JPEG or PNG bytes) at a size good for
     * recognition and reads it. Null when the image cannot be decoded.
     */
    suspend fun read(image: ByteArray, detectTurn: Boolean): PageReading? {
        val bitmap = decodeForReading(image) ?: return null
        return try {
            read(bitmap, detectTurn)
        } finally {
            bitmap.recycle()
        }
    }

    private fun reduced(page: Bitmap): Bitmap {
        val pixels = page.width.toLong() * page.height
        if (pixels <= PROBE_PIXELS) return page
        val factor = sqrt(PROBE_PIXELS.toDouble() / pixels)
        return page.scale(max(1, (page.width * factor).roundToInt()), max(1, (page.height * factor).roundToInt()))
    }

    private fun decodeForReading(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= READ_LONG_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private companion object {
        /** A page of dense text is a few thousand characters; this only guards against pathological output. */
        const val MAX_CHARACTERS = 50_000

        /** Size of the copy the other orientations are tried on: about A4 at 130 dpi. */
        const val PROBE_PIXELS = 1_300_000L

        /** Stored pages are decoded to about this long edge (halving only), like a finished scan. */
        const val READ_LONG_EDGE = 2400
    }
}
