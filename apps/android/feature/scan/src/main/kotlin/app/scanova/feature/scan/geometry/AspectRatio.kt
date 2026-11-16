package app.scanova.feature.scan.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Recovers the true proportions of a page from its outline in a photo.
 *
 * A page photographed at an angle appears as a general quadrilateral, and the
 * ratio of its side lengths in the image is not the ratio of the paper. For a
 * camera with square pixels whose optical axis passes through the image
 * centre, the four corners determine the page's proportions once the focal
 * length is known (Zhang and He, "Whiteboard Scanning and Image Enhancement",
 * 2006).
 *
 * The focal length itself can be solved from the outline only when both pairs
 * of opposite sides converge. Tilting a phone about one axis — the most common
 * way to photograph a page on a desk — leaves one pair parallel, so in that
 * case, and whenever the solved value is implausible, the focal length of a
 * typical phone main camera is assumed instead. Seen straight on, the result
 * does not depend on the focal length at all and equals the plain side ratio.
 */
object AspectRatioEstimator {
    private const val MIN_RATIO = 0.2
    private const val MAX_RATIO = 5.0
    private const val DEGENERATE = 1e-9

    /**
     * Focal length of a phone's main camera as a fraction of the image's longer
     * side (a 26 mm-equivalent lens on a 4:3 sensor is about 0.75).
     */
    private const val TYPICAL_FOCAL_FACTOR = 0.75
    private const val MIN_FOCAL_FACTOR = 0.35
    private const val MAX_FOCAL_FACTOR = 4.0

    /** Below this relative convergence a pair of sides counts as parallel. */
    private const val MIN_CONVERGENCE = 1e-3

    /** Width divided by height of the real page. */
    fun estimate(quad: Quad, imageWidth: Double, imageHeight: Double): Double {
        val ratio = perspectiveRatio(quad, imageWidth, imageHeight)?.takeIf { it.isFinite() } ?: sideLengthRatio(quad)
        return ratio.coerceIn(MIN_RATIO, MAX_RATIO)
    }

    private fun sideLengthRatio(quad: Quad): Double {
        val width = (quad.topLength + quad.bottomLength) / 2.0
        val height = (quad.leftLength + quad.rightLength) / 2.0
        return if (height <= 0.0 || width <= 0.0) 1.0 else width / height
    }

    private fun perspectiveRatio(quad: Quad, imageWidth: Double, imageHeight: Double): Double? {
        val u0 = imageWidth / 2.0
        val v0 = imageHeight / 2.0
        // Homogeneous corners relative to the principal point. Naming follows the
        // paper: m1 bottom-left, m2 bottom-right, m3 top-left, m4 top-right.
        val m1 = vector(quad.bottomLeft, u0, v0)
        val m2 = vector(quad.bottomRight, u0, v0)
        val m3 = vector(quad.topLeft, u0, v0)
        val m4 = vector(quad.topRight, u0, v0)

        val denominator2 = dot(cross(m2, m4), m3)
        val denominator3 = dot(cross(m3, m4), m2)
        if (abs(denominator2) < DEGENERATE || abs(denominator3) < DEGENERATE) return null
        val k2 = dot(cross(m1, m4), m3) / denominator2
        val k3 = dot(cross(m1, m4), m2) / denominator3

        // n2 runs along the page's width, n3 along its height.
        val n2 = doubleArrayOf(k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 * m2[2] - m1[2])
        val n3 = doubleArrayOf(k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 * m3[2] - m1[2])

        val longEdge = max(imageWidth, imageHeight)
        val focalSquared = solvedFocalSquared(n2, n3, longEdge) ?: (TYPICAL_FOCAL_FACTOR * longEdge).let { it * it }

        val width = (n2[0] * n2[0] + n2[1] * n2[1]) / focalSquared + n2[2] * n2[2]
        val height = (n3[0] * n3[0] + n3[1] * n3[1]) / focalSquared + n3[2] * n3[2]
        if (width <= 0.0 || height <= 0.0) return null
        return sqrt(width / height)
    }

    /** The focal length implied by the outline, or null when it cannot be trusted. */
    private fun solvedFocalSquared(n2: DoubleArray, n3: DoubleArray, longEdge: Double): Double? {
        // A third component near zero means that pair of sides is parallel in
        // the image; dividing by it would amplify noise into nonsense.
        if (abs(n2[2]) < MIN_CONVERGENCE || abs(n3[2]) < MIN_CONVERGENCE) return null
        val focalSquared = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
        if (!focalSquared.isFinite() || focalSquared <= 0.0) return null
        val factor = sqrt(focalSquared) / longEdge
        return focalSquared.takeIf { factor in MIN_FOCAL_FACTOR..MAX_FOCAL_FACTOR }
    }

    private fun vector(point: Point2, u0: Double, v0: Double) = doubleArrayOf(point.x - u0, point.y - v0, 1.0)

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

data class PixelSize(val width: Int, val height: Int)

/** Chooses the pixel size of the flattened page. */
object OutputSize {
    private const val MIN_EDGE = 16

    /**
     * Keeps roughly the resolution the page had in the photo (its longest
     * visible sides), reshaped to the page's true proportions, and never larger
     * than [maxLongEdge] on its longer side.
     */
    fun forQuad(quad: Quad, imageWidth: Double, imageHeight: Double, maxLongEdge: Int): PixelSize = forPage(
        visibleWidth = max(quad.topLength, quad.bottomLength),
        visibleHeight = max(quad.leftLength, quad.rightLength),
        ratio = AspectRatioEstimator.estimate(quad, imageWidth, imageHeight),
        maxLongEdge = maxLongEdge,
    )

    /**
     * As [forQuad], from the page's longest visible width and height in pixels
     * (measured along its edges, which may be curved) and its true width ÷ height.
     */
    fun forPage(visibleWidth: Double, visibleHeight: Double, ratio: Double, maxLongEdge: Int): PixelSize {
        var width = visibleWidth
        var height = visibleHeight
        if (width <= 0.0 || height <= 0.0 || !width.isFinite() || !height.isFinite()) return PixelSize(MIN_EDGE, MIN_EDGE)
        if (width / height > ratio) height = width / ratio else width = height * ratio

        val longEdge = max(width, height)
        if (longEdge > maxLongEdge) {
            val shrink = maxLongEdge / longEdge
            width *= shrink
            height *= shrink
        }
        return PixelSize(max(MIN_EDGE, width.roundToInt()), max(MIN_EDGE, height.roundToInt()))
    }
}
