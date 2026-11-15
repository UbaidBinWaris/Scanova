package app.scanova.feature.scan.geometry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Pure geometry for the scan pipeline. Nothing in this package touches Android
// or OpenCV, so it is unit-tested on the JVM. Coordinates follow image
// convention: x grows to the right, y grows downwards.

data class Point2(val x: Double, val y: Double) {
    operator fun minus(other: Point2) = Point2(x - other.x, y - other.y)

    operator fun plus(other: Point2) = Point2(x + other.x, y + other.y)

    operator fun times(factor: Double) = Point2(x * factor, y * factor)

    fun distanceTo(other: Point2): Double = hypot(x - other.x, y - other.y)
}

/** A four-sided outline of a page, corners named as they appear to the viewer. */
data class Quad(
    val topLeft: Point2,
    val topRight: Point2,
    val bottomRight: Point2,
    val bottomLeft: Point2,
) {
    /** Clockwise from the top-left corner. */
    val corners: List<Point2> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    val topLength: Double get() = topLeft.distanceTo(topRight)
    val bottomLength: Double get() = bottomLeft.distanceTo(bottomRight)
    val leftLength: Double get() = topLeft.distanceTo(bottomLeft)
    val rightLength: Double get() = topRight.distanceTo(bottomRight)

    /** Enclosed area (shoelace formula). */
    fun area(): Double {
        val points = corners
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    /** True when the outline bulges outwards everywhere and does not cross itself. */
    fun isConvex(): Boolean {
        val points = corners
        var sign = 0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val c = points[(i + 2) % points.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < EPSILON) return false
            val current = if (cross > 0) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return true
    }

    /** Interior angles in degrees, in corner order. */
    fun interiorAngles(): List<Double> {
        val points = corners
        return points.indices.map { i ->
            val previous = points[(i + points.size - 1) % points.size] - points[i]
            val next = points[(i + 1) % points.size] - points[i]
            val lengths = hypot(previous.x, previous.y) * hypot(next.x, next.y)
            if (lengths < EPSILON) {
                0.0
            } else {
                val cosine = ((previous.x * next.x + previous.y * next.y) / lengths).coerceIn(-1.0, 1.0)
                Math.toDegrees(acos(cosine))
            }
        }
    }

    fun map(transform: (Point2) -> Point2): Quad =
        Quad(transform(topLeft), transform(topRight), transform(bottomRight), transform(bottomLeft))

    /** Multiplies x by [scaleX] and y by [scaleY] (normalised ↔ pixel coordinates). */
    fun scale(scaleX: Double, scaleY: Double): Quad = map { Point2(it.x * scaleX, it.y * scaleY) }

    /** Moves every corner into the rectangle `[0, width] × [0, height]`. */
    fun clampTo(width: Double, height: Double): Quad =
        map { Point2(it.x.coerceIn(0.0, width), it.y.coerceIn(0.0, height)) }

    /** Largest distance any corner moved between this outline and [other]. */
    fun maxCornerDistance(other: Quad): Double =
        corners.zip(other.corners).maxOf { (a, b) -> a.distanceTo(b) }

    /** Returns this outline with corner [index] (0 = top-left, clockwise) replaced. */
    fun withCorner(index: Int, point: Point2): Quad = when (index) {
        0 -> copy(topLeft = point)
        1 -> copy(topRight = point)
        2 -> copy(bottomRight = point)
        3 -> copy(bottomLeft = point)
        else -> throw IndexOutOfBoundsException("A quad has four corners")
    }

    companion object {
        private const val EPSILON = 1e-9

        /** The whole frame, inset by [margin] on each side. */
        fun inset(width: Double, height: Double, margin: Double): Quad = Quad(
            Point2(margin, margin),
            Point2(width - margin, margin),
            Point2(width - margin, height - margin),
            Point2(margin, height - margin),
        )

        /**
         * Names four points found in any order (for example a contour from edge
         * detection). They are walked clockwise around their centre, starting
         * from the one nearest the frame's top-left origin. Returns null when
         * the points do not form a usable outline.
         */
        fun fromUnordered(points: List<Point2>): Quad? {
            if (points.size != 4) return null
            val centerX = points.sumOf { it.x } / 4.0
            val centerY = points.sumOf { it.y } / 4.0
            // With y pointing down, increasing atan2 angle runs clockwise on screen.
            val clockwise = points.sortedBy { atan2(it.y - centerY, it.x - centerX) }
            val start = clockwise.indices.minByOrNull { clockwise[it].x + clockwise[it].y } ?: return null
            val ordered = List(4) { clockwise[(start + it) % 4] }
            val quad = Quad(ordered[0], ordered[1], ordered[2], ordered[3])
            return if (quad.isConvex() && quad.area() > EPSILON) quad else null
        }
    }
}

/**
 * How likely a detected outline is to be a page, from 0 (reject) to 1.
 *
 * A page seen by a hand-held camera is a convex quadrilateral that fills a
 * good part of the frame, has corners not too far from right angles, and has
 * opposite sides of similar length. Small, sliver-like or strongly skewed
 * outlines are usually shadows, table edges or text blocks.
 */
object QuadScorer {
    private const val MIN_AREA_FRACTION = 0.08
    private const val MIN_ANGLE = 45.0
    private const val MAX_ANGLE = 135.0
    private const val MIN_SIDE_RATIO = 0.35

    fun score(quad: Quad, frameWidth: Double, frameHeight: Double): Double {
        if (frameWidth <= 0 || frameHeight <= 0 || !quad.isConvex()) return 0.0
        val areaFraction = quad.area() / (frameWidth * frameHeight)
        if (areaFraction < MIN_AREA_FRACTION || areaFraction > 1.0) return 0.0

        val angles = quad.interiorAngles()
        if (angles.any { it < MIN_ANGLE || it > MAX_ANGLE }) return 0.0
        val angleScore = 1.0 - angles.sumOf { abs(it - 90.0) } / (4 * 45.0)

        val horizontal = ratio(quad.topLength, quad.bottomLength)
        val vertical = ratio(quad.leftLength, quad.rightLength)
        if (horizontal < MIN_SIDE_RATIO || vertical < MIN_SIDE_RATIO) return 0.0
        val balance = (horizontal + vertical) / 2.0

        // Area matters most, with diminishing returns: a page filling 60 % of the
        // frame should beat a tidy rectangle filling 15 %.
        val areaScore = min(1.0, kotlin.math.sqrt(areaFraction) / kotlin.math.sqrt(0.7))
        return (areaScore * (0.5 + 0.5 * angleScore) * (0.5 + 0.5 * balance)).coerceIn(0.0, 1.0)
    }

    private fun ratio(a: Double, b: Double): Double {
        val longer = max(a, b)
        return if (longer <= 0.0) 0.0 else min(a, b) / longer
    }
}
