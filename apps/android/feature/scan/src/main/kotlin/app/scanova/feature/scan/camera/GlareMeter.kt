package app.scanova.feature.scan.camera

import app.scanova.feature.scan.geometry.Quad
import kotlin.math.max

/**
 * Notices a reflection on the page in the live camera picture, so the person
 * can tilt the phone *before* taking it: where a reflection burns the paper
 * to pure white, the text under it is gone from the photo and no filter can
 * bring it back.
 *
 * A reflection is a part of the page that is blown out (at or near the
 * sensor's maximum) while the rest of the paper is not. A page that is bright
 * all over is not one (the camera's exposure handles that). The answer is
 * smoothed over the last few frames so the hint does not flicker.
 */
internal class GlareMeter {

    private val recent = BooleanArray(HISTORY)
    private var next = 0

    /**
     * Whether the page in this frame shows a reflection. [luminance] is the
     * frame's brightness plane ([width] × [height], one byte per pixel);
     * [page] the page outline in fractions of that same (unrotated) frame, or
     * null when no page was found (then nothing is reported).
     */
    fun onFrame(luminance: ByteArray, width: Int, height: Int, page: Quad?): Boolean {
        val glare = page != null && hasReflection(luminance, width, height, page)
        recent[next] = glare
        next = (next + 1) % HISTORY
        return recent.count { it } >= MIN_FRAMES_WITH_GLARE
    }

    companion object {
        private const val HISTORY = 5
        private const val MIN_FRAMES_WITH_GLARE = 3

        /** Sensor levels counted as blown out. */
        private const val BLOWN_OUT = 250

        /** The paper itself must be clearly below that, or the whole page is just bright. */
        private const val MAX_PAPER_LEVEL = 238

        /** Share of the page that must be blown out: a speck of glitter is not a reflection. */
        private const val MIN_SHARE = 0.004f

        /** Above this share it is no longer a spot but an overexposed page. */
        private const val MAX_SHARE = 0.45f

        /** About this many samples along the page's longer side. */
        private const val SAMPLES_PER_SIDE = 160

        /** The pure decision for one frame (see [GlareMeter]). */
        fun hasReflection(luminance: ByteArray, width: Int, height: Int, page: Quad): Boolean {
            if (width <= 0 || height <= 0 || luminance.size < width * height) return false
            val xs = page.corners.map { it.x }
            val ys = page.corners.map { it.y }
            // The page's bounding box, pulled in a little so the background around it never counts.
            val left = ((xs.min() + INSET) * width).toInt().coerceIn(0, width - 1)
            val right = ((xs.max() - INSET) * width).toInt().coerceIn(0, width - 1)
            val top = ((ys.min() + INSET) * height).toInt().coerceIn(0, height - 1)
            val bottom = ((ys.max() - INSET) * height).toInt().coerceIn(0, height - 1)
            if (right <= left || bottom <= top) return false
            val step = max(1, max(right - left, bottom - top) / SAMPLES_PER_SIDE)
            val histogram = IntArray(256)
            var samples = 0
            var blown = 0
            var y = top
            while (y <= bottom) {
                var x = left
                val row = y * width
                while (x <= right) {
                    val level = luminance[row + x].toInt() and 0xFF
                    histogram[level]++
                    if (level >= BLOWN_OUT) blown++
                    samples++
                    x += step
                }
                y += step
            }
            if (samples == 0) return false
            val share = blown.toFloat() / samples
            if (share < MIN_SHARE || share > MAX_SHARE) return false
            return median(histogram, samples) <= MAX_PAPER_LEVEL
        }

        /** Median level of the page: mostly paper, which is what decides "bright all over". */
        private fun median(histogram: IntArray, samples: Int): Int {
            var seen = 0
            for (level in histogram.indices) {
                seen += histogram[level]
                if (seen * 2 >= samples) return level
            }
            return 255
        }

        private const val INSET = 0.02
    }
}
