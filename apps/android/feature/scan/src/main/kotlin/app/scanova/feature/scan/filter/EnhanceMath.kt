package app.scanova.feature.scan.filter

import kotlin.math.abs
import kotlin.math.hypot
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
     * [inSolidArea] (0..1) is how much the pixel lies in a solid area: a pale
     * stamp or a yellow note is not faint ink, so it is left as it is.
     */
    fun faintInkGain(depth: Float, paper: Paper, inSolidArea: Float = 0f): Float {
        val wanted = (TARGET_INK_DEPTH / max(depth, 1e-3f)).coerceIn(1f, MAX_GAIN)
        val full = 1f + (wanted - 1f) * smoothstep(paper.faintInkFrom, paper.faintInkFull, depth)
        return 1f + (full - 1f) * (1f - inSolidArea.coerceIn(0f, 1f))
    }

    /**
     * Whether a spot of the paper estimate looks like paper in less light. The
     * arguments are its three channels divided by the paper nearby. A shadow
     * lowers every channel, by similar factors, and the light that is left
     * changes colour smoothly with wavelength (warmer or cooler), so green is
     * lowered between red and blue. Coloured ink lowers some channels far more
     * than others or out of that order (purple, green), black ink lowers all of
     * them further, and a pale coloured area leaves some almost as they were.
     */
    fun isDimmedPaper(red: Float, green: Float, blue: Float): Boolean {
        val low = minOf(red, green, blue)
        val high = maxOf(red, green, blue)
        val greenLow = minOf(red, blue) / GREEN_TOLERANCE
        val greenHigh = maxOf(red, blue) * GREEN_TOLERANCE
        return low >= MIN_DIMMED && high <= MAX_DIMMED && high <= low * MAX_TINT && green in greenLow..greenHigh
    }

    /**
     * Connected regions of a mask of [cols] × [rows] pixels, row by row.
     * [labels] holds each pixel's region (0 outside the mask, regions from 1);
     * [areas] and [touchesBorder] are indexed by region (index 0 unused).
     */
    class Regions(val labels: IntArray, val areas: IntArray, val touchesBorder: BooleanArray)

    /** The connected regions of [mask]; [diagonal] also joins pixels that only touch at a corner. */
    fun regions(mask: BooleanArray, cols: Int, rows: Int, diagonal: Boolean): Regions {
        require(mask.size == cols * rows) { "mask is not $cols × $rows" }
        val labels = IntArray(mask.size)
        val queue = IntArray(mask.size)
        val areas = ArrayList<Int>().apply { add(0) }
        val borders = ArrayList<Boolean>().apply { add(false) }
        val neighbours = if (diagonal) 8 else 4
        for (start in mask.indices) {
            if (!mask[start] || labels[start] != 0) continue
            val label = areas.size
            labels[start] = label
            var head = 0
            var tail = 0
            queue[tail++] = start
            var border = false
            while (head < tail) {
                val pixel = queue[head++]
                val x = pixel % cols
                val y = pixel / cols
                if (x == 0 || y == 0 || x == cols - 1 || y == rows - 1) border = true
                for (step in 0 until neighbours) {
                    val nx = x + STEP_X[step]
                    val ny = y + STEP_Y[step]
                    if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue
                    val next = ny * cols + nx
                    if (mask[next] && labels[next] == 0) {
                        labels[next] = label
                        queue[tail++] = next
                    }
                }
            }
            areas += tail
            borders += border
        }
        return Regions(labels, areas.toIntArray(), borders.toBooleanArray())
    }

    /**
     * Which pixels of the working-size paper estimate lie in a shadow. A shadow
     * is not a solid area: it keeps its own estimate, so it is evened out like
     * the rest of the light (if only its rim counted as solid, it came out as a
     * white centre with a dark, coloured rim). The decision is made per region
     * of [candidates] (far below the paper nearby, and [isDimmedPaper]) by
     * [isShadow], from the pixels of the region marked [inside] (its interior,
     * away from its own edge): [gradient] is the change of brightness per pixel
     * relative to the brightness, [hueRedGreen] and [hueBlueGreen] the
     * logarithmic colour (ln red − ln green, ln blue − ln green).
     */
    fun shadows(
        candidates: BooleanArray,
        inside: BooleanArray,
        gradient: FloatArray,
        hueRedGreen: FloatArray,
        hueBlueGreen: FloatArray,
        cols: Int,
        rows: Int,
    ): BooleanArray {
        val longSide = max(cols, rows)
        val found = regions(candidates, cols, rows, diagonal = true)
        val count = IntArray(found.areas.size)
        for (index in candidates.indices) if (inside[index] && candidates[index]) count[found.labels[index]]++
        val considered = BooleanArray(found.areas.size) { label ->
            label != 0 && isLargeAtEdge(found.areas[label], found.touchesBorder[label], longSide) && count[label] >= MIN_SHADOW_INSIDE
        }
        val gradients = Array(found.areas.size) { if (considered[it]) FloatArray(count[it]) else EMPTY }
        val hueA = Array(found.areas.size) { if (considered[it]) FloatArray(count[it]) else EMPTY }
        val hueB = Array(found.areas.size) { if (considered[it]) FloatArray(count[it]) else EMPTY }
        val filled = IntArray(found.areas.size)
        for (index in candidates.indices) {
            val label = found.labels[index]
            if (!considered[label] || !inside[index]) continue
            val at = filled[label]++
            gradients[label][at] = gradient[index]
            hueA[label][at] = hueRedGreen[index]
            hueB[label][at] = hueBlueGreen[index]
        }
        val shadow = BooleanArray(found.areas.size) { label ->
            considered[label] && isShadow(found.areas[label], found.touchesBorder[label], longSide, gradients[label], hueA[label], hueB[label])
        }
        return BooleanArray(candidates.size) { shadow[found.labels[it]] }
    }

    /**
     * Whether a region of dimmed paper is a shadow: it reaches the page's edge (a
     * hand or a phone casts it from outside the page), is large, and its inside
     * is as smooth and evenly coloured as paper. Half of the inside changes by at
     * most [SHADOW_MEDIAN_GRADIENT] of its brightness per pixel and three
     * quarters by at most [SHADOW_UPPER_GRADIENT], and its hue varies by at most
     * [SHADOW_HUE_SPREAD] (robust spread). A photo, a printed colour area or a
     * textured desk has edges, many tones and varying hue, and is left alone: a
     * shadow left in place is better than a picture wiped out.
     */
    fun isShadow(
        area: Int,
        touchesBorder: Boolean,
        longSide: Int,
        insideGradients: FloatArray,
        insideHueRedGreen: FloatArray,
        insideHueBlueGreen: FloatArray,
    ): Boolean {
        if (!isLargeAtEdge(area, touchesBorder, longSide) || insideGradients.size < MIN_SHADOW_INSIDE) return false
        val gradients = insideGradients.sortedArray()
        return quantile(gradients, 0.5f) <= SHADOW_MEDIAN_GRADIENT &&
            quantile(gradients, 0.75f) <= SHADOW_UPPER_GRADIENT &&
            hypot(spread(insideHueRedGreen), spread(insideHueBlueGreen)) <= SHADOW_HUE_SPREAD
    }

    private fun isLargeAtEdge(area: Int, touchesBorder: Boolean, longSide: Int): Boolean {
        val side = longSide / SHADOW_MIN_SIDE_DIVISOR
        return touchesBorder && area >= side * side
    }

    /** The [fraction] quantile of [sorted] values, interpolated between neighbours (as numpy's percentile). */
    fun quantile(sorted: FloatArray, fraction: Float): Float {
        if (sorted.isEmpty()) return 0f
        val position = fraction * (sorted.size - 1)
        val below = position.toInt()
        val above = minOf(below + 1, sorted.size - 1)
        return sorted[below] + (sorted[above] - sorted[below]) * (position - below)
    }

    /** Robust spread: the median absolute deviation, scaled to a standard deviation. */
    fun spread(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val middle = median(values)
        return MAD_TO_SIGMA * median(FloatArray(values.size) { abs(values[it] - middle) })
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

    /** Dimmed paper keeps at least this share of the light in every channel (deeper is ink)… */
    const val MIN_DIMMED = 0.25f

    /** …and loses at least a tenth in every channel. */
    const val MAX_DIMMED = 0.90f

    /** Its channels are lowered by factors within e^0.6 ≈ 1.82 of each other (a warm or cool shadow). */
    const val MAX_TINT = 1.8221188f

    /** Green may lie this factor outside the range of red and blue. */
    const val GREEN_TOLERANCE = 1.05f

    /** A shadow region covers at least a square of (long side / 16)². */
    const val SHADOW_MIN_SIDE_DIVISOR = 16f

    /** A region needs this many pixels inside (away from its edge) to be judged a shadow. */
    const val MIN_SHADOW_INSIDE = 200

    /** Inside a shadow, half the pixels change by at most this share of the brightness per pixel… */
    const val SHADOW_MEDIAN_GRADIENT = 0.0007f

    /** …and three quarters by at most this share. */
    const val SHADOW_UPPER_GRADIENT = 0.003f

    /** Largest robust spread of a shadow's hue (ln red − ln green and ln blue − ln green, combined). */
    const val SHADOW_HUE_SPREAD = 0.03f

    private val EMPTY = FloatArray(0)

    /** Neighbour offsets: the first four share a side, the last four a corner. */
    private val STEP_X = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val STEP_Y = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    private const val MAD_TO_SIGMA = 1.4826f

    /** A 3×3 median residual underestimates the noise; calibrated on synthetic Gaussian noise. */
    private const val NOISE_CALIBRATION = 1.25f
}
