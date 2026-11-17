package app.scanova.feature.scan.camera

import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import kotlin.math.ceil
import kotlin.math.max

/**
 * Finds a glare spot on a page: a patch where a reflection (the flash, a lamp,
 * a window) burns the paper to the top of the sensor's range. The print under
 * such a patch is not in the picture at all. No filter can bring it back, and
 * the app never draws letters it did not see, so the only fix is another photo.
 *
 * A glare spot is
 * - **blown out**: its cells sit at the very top of the brightness range;
 * - **brighter than the paper**: the rest of the page is clearly below that.
 *   A page that is bright all over is an exposure matter, not a reflection;
 * - **one patch of a size that matters next to the page**: specks of glitter,
 *   foil or a shiny staple are ignored, however many of them there are.
 *
 * It works on a grid of cells (each the mean of a square block of pixels)
 * laid over the page, at most [GRID_CELLS] along its longer side, so the work
 * does not grow with the camera's resolution and a spot is judged by its share
 * of the page, whatever the distance. One call reads each pixel of the page's
 * bounding box once: about 0.3 MP for a 640 × 480 preview frame.
 *
 * Arrays in, a number out ([largestSpot]); [GlareMeter] smooths it over
 * preview frames, and the photo is checked once it is taken. Buffers are
 * reused between calls, so one instance belongs to one thread.
 */
internal class GlareSpotFinder {

    /** Per cell: its mean brightness, [OUTSIDE] the page, or [VISITED] once counted in a patch. */
    private val levels = IntArray(GRID_CELLS * GRID_CELLS)
    private val stack = IntArray(GRID_CELLS * GRID_CELLS)
    private val histogram = IntArray(LEVELS)

    /**
     * The largest glare spot's share of the page, 0..1 (0 when there is none).
     *
     * [luminance] is the brightness plane, [width] × [height], one byte per pixel,
     * rows packed. [page] is the page outline in fractions of that frame; null
     * when no page was found, and then the central part of the frame is looked at.
     */
    fun largestSpot(luminance: ByteArray, width: Int, height: Int, page: Quad?): Float {
        if (width <= 0 || height <= 0 || luminance.size < width * height) return 0f
        // Pulled in from the page's edges: an outline a little off must not count the table as paper.
        val area = shrink(page ?: CENTRAL_AREA, 1.0 - 2 * EDGE_INSET)
        val xs = DoubleArray(CORNERS) { area.corners[it].x * width }
        val ys = DoubleArray(CORNERS) { area.corners[it].y * height }
        val left = xs.min().toInt().coerceIn(0, width)
        val right = ceil(xs.max()).toInt().coerceIn(0, width)
        val top = ys.min().toInt().coerceIn(0, height)
        val bottom = ceil(ys.max()).toInt().coerceIn(0, height)
        val step = max(1, ceil(max(right - left, bottom - top) / GRID_CELLS.toDouble()).toInt())
        val cols = (right - left) / step
        val rows = (bottom - top) / step
        if (cols < 2 || rows < 2) return 0f

        // Per cell: the mean finds the spot; the brightest pixel is the paper's white
        // even where print shares the cell (a mean would mix the two).
        histogram.fill(0)
        var inside = 0
        val blockSize = step * step
        for (row in 0 until rows) {
            val y0 = top + row * step
            val centerY = y0 + step / 2.0
            for (col in 0 until cols) {
                val x0 = left + col * step
                val cell = row * cols + col
                if (!contains(xs, ys, x0 + step / 2.0, centerY)) {
                    levels[cell] = OUTSIDE
                    continue
                }
                var sum = 0
                var brightest = 0
                for (y in y0 until y0 + step) {
                    val offset = y * width
                    for (x in x0 until x0 + step) {
                        val level = luminance[offset + x].toInt() and 0xFF
                        sum += level
                        if (level > brightest) brightest = level
                    }
                }
                levels[cell] = sum / blockSize
                histogram[brightest]++
                inside++
            }
        }
        if (inside < MIN_CELLS) return 0f

        // The paper's white across the page. Near the top of the range (an overexposed
        // page, or one mostly glare) a reflection cannot be told from the paper itself,
        // and the camera's exposure deals with that; too dark, this is not lit paper.
        val paperWhite = median(inside)
        if (paperWhite > MAX_PAPER_WHITE || paperWhite < MIN_PAPER_WHITE) return 0f

        val largest = largestPatch(cols, rows)
        return if (largest < MIN_SPOT_CELLS) 0f else largest.toFloat() / inside
    }

    /** The median of the [count] values in [histogram]. */
    private fun median(count: Int): Int {
        var seen = 0
        for (level in 0 until LEVELS) {
            seen += histogram[level]
            if (seen * 2 >= count) return level
        }
        return LEVELS - 1
    }

    /** Cells in the largest group of touching (8 neighbours) blown-out cells. */
    private fun largestPatch(cols: Int, rows: Int): Int {
        var largest = 0
        for (start in 0 until cols * rows) {
            if (levels[start] < BLOWN_OUT) continue
            levels[start] = VISITED
            var size = 0
            var top = 0
            stack[top++] = start
            while (top > 0) {
                val cell = stack[--top]
                size++
                val row = cell / cols
                val col = cell % cols
                for (dy in -1..1) {
                    val y = row + dy
                    if (y < 0 || y >= rows) continue
                    for (dx in -1..1) {
                        val x = col + dx
                        if (x < 0 || x >= cols) continue
                        val next = y * cols + x
                        if (levels[next] >= BLOWN_OUT) {
                            levels[next] = VISITED
                            stack[top++] = next
                        }
                    }
                }
            }
            if (size > largest) largest = size
        }
        return largest
    }

    companion object {
        /** A spot at least this share of the page hides enough print to ask for another photo (about 1.5 cm across on A4). */
        const val MIN_SPOT_SHARE = 0.004f

        /** Whether a spot of [share] ([largestSpot]) is worth telling the person about. */
        fun isGlare(share: Float): Boolean = share >= MIN_SPOT_SHARE

        /** The page as a whole, for an image that shows only the page (a flattened photo). */
        val WHOLE_PAGE: Quad = Quad.inset(1.0, 1.0, 0.0)

        /**
         * Brightness (BT.601, as the camera's Y plane) of packed ARGB pixels,
         * one byte each: what [largestSpot] reads.
         */
        fun luminanceOf(argb: IntArray): ByteArray = ByteArray(argb.size) { index ->
            val pixel = argb[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            ((RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue + HALF) shr WEIGHT_BITS).toByte()
        }

        /** At most this many cells along the page's longer side. */
        const val GRID_CELLS = 120

        /** A cell this bright (mean, 0..255) is blown out. */
        private const val BLOWN_OUT = 244

        /** The paper's white (median of the cells' brightest pixels) must stay below this for a blown-out patch to stand out. */
        private const val MAX_PAPER_WHITE = 236

        /** Darker than this, what is looked at is not lit paper (a dark room, the floor). */
        private const val MIN_PAPER_WHITE = 70

        /** Fewer cells on the page than this: too small to judge. */
        private const val MIN_CELLS = 64

        /** A spot of fewer cells than this is noise, however small the page. */
        private const val MIN_SPOT_CELLS = 4

        /** Share of each side left out at the page's edges. */
        private const val EDGE_INSET = 0.05

        /** Without a page outline, the middle of the frame (this margin on each side). */
        private val CENTRAL_AREA = Quad.inset(1.0, 1.0, 0.2)

        private const val OUTSIDE = -1
        private const val VISITED = -2
        private const val LEVELS = 256
        private const val CORNERS = 4
        private const val RED_WEIGHT = 77
        private const val GREEN_WEIGHT = 150
        private const val BLUE_WEIGHT = 29
        private const val HALF = 128
        private const val WEIGHT_BITS = 8

        /** [quad] scaled by [factor] around its centre. */
        private fun shrink(quad: Quad, factor: Double): Quad {
            val center = Point2(quad.corners.sumOf { it.x } / CORNERS, quad.corners.sumOf { it.y } / CORNERS)
            return quad.map { center + (it - center) * factor }
        }

        /** Whether ([x], [y]) is inside the convex polygon ([xs], [ys]), whichever way it winds. */
        private fun contains(xs: DoubleArray, ys: DoubleArray, x: Double, y: Double): Boolean {
            var positive = false
            var negative = false
            for (i in 0 until CORNERS) {
                val j = (i + 1) % CORNERS
                val cross = (xs[j] - xs[i]) * (y - ys[i]) - (ys[j] - ys[i]) * (x - xs[i])
                if (cross > 0) positive = true else if (cross < 0) negative = true
                if (positive && negative) return false
            }
            return true
        }
    }
}
