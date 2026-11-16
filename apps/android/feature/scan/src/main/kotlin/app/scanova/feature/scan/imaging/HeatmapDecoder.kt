package app.scanova.feature.scan.imaging

import app.scanova.feature.scan.geometry.Point2

/**
 * Turns the page model's four corner heatmaps into corner points
 * (docs/ai-image-research.md, "Finding the page").
 *
 * Each heatmap says, per cell, how likely that cell is the corner. Where the
 * model is confident, the corner is the value-weighted centre of the largest
 * region above [THRESHOLD], as in DocAligner's own decoding (which reproduces its
 * published SmartDoc score, Jaccard 0.989). Where it is not, the weighted peak is
 * used instead, so one faint corner still gives a page. A heatmap that never
 * rises above [FLOOR] means there is no page.
 *
 * Pure Kotlin: no Android, no OpenCV, so it is tested on the JVM.
 */
internal object HeatmapDecoder {
    const val THRESHOLD = 0.3f
    const val FLOOR = 0.05f

    /**
     * [maps] holds [count] heatmaps of [size] × [size] cells, row by row, one
     * after the other (the model's `1 × 4 × 128 × 128` output, flattened).
     * Returns one point per heatmap as fractions of the image (0..1), or null.
     */
    fun decode(maps: FloatArray, count: Int, size: Int): List<Point2>? {
        require(maps.size >= count * size * size) { "heatmap_too_short" }
        return (0 until count).map { channel ->
            corner(maps, channel * size * size, size) ?: return null
        }
    }

    private fun corner(maps: FloatArray, offset: Int, size: Int): Point2? {
        var peak = offset
        for (i in offset until offset + size * size) if (maps[i] > maps[peak]) peak = i
        if (maps[peak] < FLOOR) return null
        val cell = if (maps[peak] >= THRESHOLD) largestRegionCentre(maps, offset, size) else null
        val (x, y) = cell ?: weightedPeak(maps, offset, size, peak - offset)
        // Cell centres: cell i covers [i, i + 1) of the map.
        return Point2((x + 0.5) / size, (y + 0.5) / size)
    }

    /** Value-weighted centre of the largest 8-connected region at or above [THRESHOLD]. */
    private fun largestRegionCentre(maps: FloatArray, offset: Int, size: Int): Pair<Double, Double>? {
        val region = IntArray(size * size)
        val stack = IntArray(size * size)
        var label = 0
        var best: Triple<Int, Double, Pair<Double, Double>>? = null // area, weight, centre
        for (start in 0 until size * size) {
            if (region[start] != 0 || maps[offset + start] < THRESHOLD) continue
            label++
            var top = 0
            stack[top++] = start
            region[start] = label
            var area = 0
            var weight = 0.0
            var sumX = 0.0
            var sumY = 0.0
            while (top > 0) {
                val cell = stack[--top]
                val cx = cell % size
                val cy = cell / size
                val value = maps[offset + cell].toDouble()
                area++
                weight += value
                sumX += cx * value
                sumY += cy * value
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 0 until size || ny !in 0 until size) continue
                    val next = ny * size + nx
                    if (region[next] == 0 && maps[offset + next] >= THRESHOLD) {
                        region[next] = label
                        stack[top++] = next
                    }
                }
            }
            if (best == null || area > best.first) best = Triple(area, weight, sumX / weight to sumY / weight)
        }
        return best?.third
    }

    /** Centre of the 5 × 5 cells around the peak, weighted by how far each rises above half the peak. */
    private fun weightedPeak(maps: FloatArray, offset: Int, size: Int, peak: Int): Pair<Double, Double> {
        val px = peak % size
        val py = peak / size
        val floor = maps[offset + peak] * 0.5f
        var weight = 0.0
        var sumX = 0.0
        var sumY = 0.0
        for (y in maxOf(0, py - 2)..minOf(size - 1, py + 2)) for (x in maxOf(0, px - 2)..minOf(size - 1, px + 2)) {
            val value = (maps[offset + y * size + x] - floor).toDouble()
            if (value <= 0) continue
            weight += value
            sumX += x * value
            sumY += y * value
        }
        return if (weight > 0) sumX / weight to sumY / weight else px.toDouble() to py.toDouble()
    }
}
