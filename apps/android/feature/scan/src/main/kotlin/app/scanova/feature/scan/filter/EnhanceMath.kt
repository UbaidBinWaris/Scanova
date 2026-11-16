package app.scanova.feature.scan.filter

import kotlin.math.abs
import kotlin.math.max

/**
 * The decisions of [DocumentEnhancer] that are plain arithmetic, kept apart
 * from OpenCV so they can be tested on any machine.
 */
internal object EnhanceMath {

    /** 0 below [edge0], 1 above [edge1], a smooth S-curve between. */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Brightness and spread of the paper, measured on the evened-out page (paper ≈ 1). */
    data class Paper(val level: Float, val spread: Float) {
        /** Everything within about three spreads of the paper becomes pure white: paper grain and noise vanish. */
        val whitePoint: Float get() = (level - 3f * spread).coerceIn(MIN_WHITE, MAX_WHITE)

        /** Ink only counts as "faint ink worth recovering" when it stands this far above the paper's own noise. */
        val faintInkFrom: Float get() = max(0.06f, 5f * spread)
        val faintInkFull: Float get() = max(0.16f, 9f * spread)
    }

    /**
     * Median and robust spread (median absolute deviation, scaled to a standard
     * deviation) of the pixels that are paper: brighter than [PAPER_FLOOR]. A
     * page that is mostly ink falls back to all pixels.
     */
    fun paper(gray: FloatArray): Paper {
        var candidates = gray.filter { it > PAPER_FLOOR }.toFloatArray()
        if (candidates.size < MIN_PAPER_SAMPLES) candidates = gray.copyOf()
        if (candidates.isEmpty()) return Paper(1f, 0f)
        val level = median(candidates)
        val deviations = FloatArray(candidates.size) { abs(candidates[it] - level) }
        return Paper(level, MAD_TO_SIGMA * median(deviations))
    }

    /**
     * How much to deepen the ink near a pixel whose darkest nearby ink is only
     * [depth] below the paper (glare or a shadow washed it out): enough to bring
     * it to [TARGET_INK_DEPTH], at most [MAX_GAIN] times, and not at all where
     * the "ink" is no deeper than the paper's noise (blank paper stays blank).
     */
    fun faintInkGain(depth: Float, paper: Paper): Float {
        val wanted = (TARGET_INK_DEPTH / max(depth, 1e-3f)).coerceIn(1f, MAX_GAIN)
        return 1f + (wanted - 1f) * smoothstep(paper.faintInkFrom, paper.faintInkFull, depth)
    }

    /** Noise level (0..1 units) from a histogram of |pixel − its 3×3 median|, binned in units of 1/[binsPerUnit]. */
    fun noiseSigma(absResidualHistogram: FloatArray, binsPerUnit: Float): Float {
        val total = absResidualHistogram.sum()
        if (total <= 0f) return 0f
        var seen = 0f
        for ((bin, count) in absResidualHistogram.withIndex()) {
            seen += count
            if (seen >= total / 2f) return MAD_TO_SIGMA * NOISE_CALIBRATION * (bin + 0.5f) / binsPerUnit
        }
        return MAD_TO_SIGMA * NOISE_CALIBRATION * absResidualHistogram.size / binsPerUnit
    }

    /**
     * Whether a dark speck is noise rather than ink. Areas are in pixels of a
     * page whose longer side is [longEdge] px; [darkest] is the darkest
     * value inside the speck (0 = black, 1 = white); [glare] how much of a
     * reflection it sits in (0..1). Real strokes are larger or darker.
     */
    fun isSpeck(area: Int, darkest: Float, glare: Float, longEdge: Int): Boolean {
        val unit = (longEdge / 1000f) * (longEdge / 1000f)
        return area < 3f * unit ||
            (area < 40f * unit && darkest > 0.62f) ||
            (glare > 0.35f && area < 80f * unit && darkest > 0.55f)
    }

    /** Saturation curve: colourful pixels get [boost] times their saturation, grey ones (paper, black ink) stay grey. */
    fun saturation(s: Float, boost: Float): Float =
        (s * (1f + (boost - 1f) * smoothstep(0.12f, 0.30f, s))).coerceIn(0f, 1f)

    private fun median(values: FloatArray): Float {
        val sorted = values.copyOf().also { it.sort() }
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    const val PAPER_FLOOR = 0.72f
    const val MIN_PAPER_SAMPLES = 100
    const val MIN_WHITE = 0.70f
    const val MAX_WHITE = 0.97f
    /**
     * Washed-out ink is brought back to this depth below the paper. Ordinary
     * coloured text is about this deep already, so it keeps its colour; only
     * ink lighter than this is deepened (and never past darker ink).
     */
    const val TARGET_INK_DEPTH = 0.50f
    const val MAX_GAIN = 3f
    private const val MAD_TO_SIGMA = 1.4826f

    /** A 3×3 median residual underestimates the noise; calibrated on synthetic Gaussian noise. */
    private const val NOISE_CALIBRATION = 1.25f
}
