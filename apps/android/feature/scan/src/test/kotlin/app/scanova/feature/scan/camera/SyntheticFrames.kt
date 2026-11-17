package app.scanova.feature.scan.camera

import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import kotlin.math.hypot

/**
 * Made-up camera brightness planes for the glare tests: a printed page on a
 * dark table, optionally with glare. No real photos are used.
 */
internal object SyntheticFrames {

    const val WIDTH = 640
    const val HEIGHT = 480

    /** The page in the middle of the frame, as fractions. */
    val PAGE = Quad(Point2(0.125, 0.125), Point2(0.875, 0.125), Point2(0.875, 0.875), Point2(0.125, 0.875))

    /** A round spot of light: blown out within [radius] px, fading into the paper over a rim as wide again. */
    class Spot(val x: Int, val y: Int, val radius: Double, val level: Int = 255)

    /**
     * A frame: the table at [table], the paper of [page] at [paper] with lines
     * of words printed at [ink], and [spots] of light on top (on the paper and
     * on the table alike).
     */
    fun frame(
        paper: Int = 200,
        ink: Int = 50,
        table: Int = 60,
        page: Quad? = PAGE,
        spots: List<Spot> = emptyList(),
    ): ByteArray {
        val pixels = ByteArray(WIDTH * HEIGHT)
        val xs = page?.corners?.map { it.x * WIDTH }
        val ys = page?.corners?.map { it.y * HEIGHT }
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val onPage = xs == null || ys == null || inside(xs, ys, x + 0.5, y + 0.5)
                val printed = y % LINE_PITCH in LINE_TOP..LINE_BOTTOM && (x / WORD_PITCH) % WORDS_PER_GAP != 0
                var level = when {
                    !onPage -> table
                    printed -> ink
                    else -> paper
                }
                for (spot in spots) {
                    val distance = hypot((x - spot.x).toDouble(), (y - spot.y).toDouble())
                    level = when {
                        distance <= spot.radius -> spot.level
                        distance <= spot.radius * 2 -> {
                            // The rim: the spot's light added to what is there, fading out.
                            val fade = 1 - (distance - spot.radius) / spot.radius
                            (level + (spot.level - level) * fade * fade).toInt()
                        }
                        else -> level
                    }
                }
                pixels[y * WIDTH + x] = level.coerceIn(0, 255).toByte()
            }
        }
        return pixels
    }

    /** Pixels at [level] or above, as a share of the page's area: how much of the page they cover in total. */
    fun share(pixels: ByteArray, level: Int, page: Quad = PAGE): Double {
        val count = pixels.count { (it.toInt() and 0xFF) >= level }
        return count / (page.area() * WIDTH * HEIGHT)
    }

    private fun inside(xs: List<Double>, ys: List<Double>, x: Double, y: Double): Boolean {
        var positive = false
        var negative = false
        for (i in xs.indices) {
            val j = (i + 1) % xs.size
            val cross = (xs[j] - xs[i]) * (y - ys[i]) - (ys[j] - ys[i]) * (x - xs[i])
            if (cross > 0) positive = true else if (cross < 0) negative = true
        }
        return !(positive && negative)
    }

    private const val LINE_PITCH = 12
    private const val LINE_TOP = 4
    private const val LINE_BOTTOM = 6
    private const val WORD_PITCH = 18
    private const val WORDS_PER_GAP = 4
}
