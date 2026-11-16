package app.scanova.feature.scan.session

import android.graphics.Bitmap
import app.scanova.core.common.log.ScanovaLog
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.imaging.PageFlattener
import javax.inject.Inject

/**
 * Asks the flattening model ([PageFlattener]) about one page: its mesh and
 * how bent the page is. The model reads a 488 × 712 view of the page, so the
 * photo is decoded at a moderate size for it. Never fails: without an answer
 * the page is flattened by its outline. Heavy work: call on a background dispatcher.
 */
internal class CurlExaminer @Inject constructor() {

    /** The model's answer for the page inside [outline] (fractions; null: the whole photo) of the upright JPEG [uprightJpeg]. */
    fun examine(uprightJpeg: ByteArray, outline: Quad?): Flattening {
        // No photo is decoded for a model that cannot run.
        if (!PageFlattener.canRun) return Flattening(outline, mesh = null, bend = Double.NaN)
        val photo = try {
            Bitmaps.decodeWithin(uprightJpeg, SOURCE_LONG_EDGE, MIN_SOURCE_LONG_EDGE)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return Flattening(outline, mesh = null, bend = Double.NaN)
        try {
            return examine(photo, outline)
        } finally {
            photo.recycle()
        }
    }

    /** As above, for an upright photo already decoded. [upright] is left alone. */
    fun examine(upright: Bitmap, outline: Quad?): Flattening {
        val found = try {
            PageFlattener.flatten(upright, outline)
        } catch (e: OutOfMemoryError) {
            ScanovaLog.warn(TAG, "flatten_out_of_memory")
            null
        }
        return Flattening(outline, found?.mesh, found?.bend ?: Double.NaN)
    }

    companion object {
        private const val TAG = "CurlExaminer"

        /** Long side the photo is decoded at for the model: the page box stays well above its 712 px input. */
        const val SOURCE_LONG_EDGE = 1600
        private const val MIN_SOURCE_LONG_EDGE = 800
    }
}
