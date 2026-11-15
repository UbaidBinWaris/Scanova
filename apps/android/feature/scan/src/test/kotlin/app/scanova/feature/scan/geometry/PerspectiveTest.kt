package app.scanova.feature.scan.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerspectiveTest {

    private val imageWidth = 3000.0
    private val imageHeight = 4000.0

    /**
     * Photographs a `width × height` sheet with an ideal pinhole camera: the
     * sheet is tilted by [tiltX] and [tiltY] (radians) and turned in its own
     * plane by [roll], then placed [distance] in front of a camera with focal
     * length [focal] (pixels) whose axis hits the centre of the image.
     */
    private fun photograph(
        width: Double,
        height: Double,
        tiltX: Double,
        tiltY: Double,
        roll: Double = 0.0,
        distance: Double = 500.0,
        focal: Double = 3200.0,
    ): Quad {
        fun project(x: Double, y: Double): Point2 {
            // Roll in the page plane.
            val x0 = x * cos(roll) - y * sin(roll)
            val y0 = x * sin(roll) + y * cos(roll)
            // Rotate about the x axis.
            val y1 = y0 * cos(tiltX)
            val z1 = y0 * sin(tiltX)
            // Rotate about the y axis.
            val x2 = x0 * cos(tiltY) + z1 * sin(tiltY)
            val z2 = -x0 * sin(tiltY) + z1 * cos(tiltY)
            val depth = distance + z2
            return Point2(imageWidth / 2 + focal * x2 / depth, imageHeight / 2 + focal * y1 / depth)
        }
        return Quad(
            project(-width / 2, -height / 2),
            project(width / 2, -height / 2),
            project(width / 2, height / 2),
            project(-width / 2, height / 2),
        )
    }

    private fun assertRatio(expected: Double, actual: Double, tolerance: Double = 0.02) {
        assertTrue("expected ratio $expected ± ${tolerance * 100}% but was $actual", abs(actual - expected) / expected < tolerance)
    }

    // ── Aspect ratio ────────────────────────────────────────────────────

    @Test
    fun `a page seen straight on keeps its proportions`() {
        val a4 = photograph(210.0, 297.0, tiltX = 0.0, tiltY = 0.0)

        assertRatio(210.0 / 297.0, AspectRatioEstimator.estimate(a4, imageWidth, imageHeight), tolerance = 1e-6)
    }

    @Test
    fun `an A4 page photographed at an angle is recognised as A4`() {
        val tilts = listOf(0.35 to 0.0, 0.0 to 0.3, 0.3 to 0.25, -0.4 to 0.2, 0.25 to -0.35, 0.5 to 0.4)
        for ((tiltX, tiltY) in tilts) {
            val quad = photograph(210.0, 297.0, tiltX, tiltY)

            // Measuring sides in the image would be off by much more than this.
            assertRatio(210.0 / 297.0, AspectRatioEstimator.estimate(quad, imageWidth, imageHeight))
        }
    }

    @Test
    fun `perspective correction beats naive side measurement`() {
        val quad = photograph(210.0, 297.0, tiltX = 0.5, tiltY = 0.0)
        val naive = ((quad.topLength + quad.bottomLength) / 2) / ((quad.leftLength + quad.rightLength) / 2)
        val estimated = AspectRatioEstimator.estimate(quad, imageWidth, imageHeight)
        val truth = 210.0 / 297.0

        assertTrue(abs(naive - truth) / truth > 0.08)
        assertTrue(abs(estimated - truth) < abs(naive - truth) / 4)
    }

    @Test
    fun `landscape, square and receipt shapes are recovered`() {
        assertRatio(297.0 / 210.0, AspectRatioEstimator.estimate(photograph(297.0, 210.0, 0.3, 0.2), imageWidth, imageHeight))
        assertRatio(1.0, AspectRatioEstimator.estimate(photograph(200.0, 200.0, 0.3, -0.3), imageWidth, imageHeight))
        assertRatio(80.0 / 300.0, AspectRatioEstimator.estimate(photograph(80.0, 300.0, 0.3, 0.15), imageWidth, imageHeight))
    }

    @Test
    fun `a page rotated in the frame is still measured correctly`() {
        val quad = photograph(210.0, 297.0, tiltX = 0.3, tiltY = 0.2, roll = 0.25)

        assertRatio(210.0 / 297.0, AspectRatioEstimator.estimate(quad, imageWidth, imageHeight))
    }

    @Test
    fun `degenerate outlines give a sane ratio instead of nonsense`() {
        val line = Quad(Point2(0.0, 0.0), Point2(100.0, 0.0), Point2(100.0, 0.0), Point2(0.0, 0.0))
        val ratio = AspectRatioEstimator.estimate(line, imageWidth, imageHeight)

        assertTrue(ratio.isFinite())
        assertTrue(ratio in 0.2..5.0)
    }

    // ── Output size ─────────────────────────────────────────────────────

    @Test
    fun `output has the page's proportions and respects the size limit`() {
        val quad = photograph(210.0, 297.0, tiltX = 0.35, tiltY = 0.2)

        val size = OutputSize.forQuad(quad, imageWidth, imageHeight, maxLongEdge = 2400)

        assertTrue(maxOf(size.width, size.height) <= 2400)
        assertRatio(210.0 / 297.0, size.width.toDouble() / size.height)
    }

    @Test
    fun `a small page is not blown up to the limit`() {
        val quad = Quad(Point2(100.0, 100.0), Point2(500.0, 100.0), Point2(500.0, 700.0), Point2(100.0, 700.0))

        val size = OutputSize.forQuad(quad, imageWidth, imageHeight, maxLongEdge = 2400)

        assertEquals(PixelSize(400, 600), size)
    }

    @Test
    fun `output size never collapses to zero`() {
        val dot = Quad(Point2(5.0, 5.0), Point2(5.0, 5.0), Point2(5.0, 5.0), Point2(5.0, 5.0))

        val size = OutputSize.forQuad(dot, imageWidth, imageHeight, maxLongEdge = 2400)

        assertTrue(size.width >= 16 && size.height >= 16)
    }

    // ── Homography ──────────────────────────────────────────────────────

    @Test
    fun `flattening maps each corner of the outline to a corner of the page`() {
        val quad = photograph(210.0, 297.0, tiltX = 0.4, tiltY = -0.3)
        val flatten = Homography.quadToRectangle(quad, 2100.0, 2970.0)!!

        assertPoint(Point2(0.0, 0.0), flatten.map(quad.topLeft))
        assertPoint(Point2(2100.0, 0.0), flatten.map(quad.topRight))
        assertPoint(Point2(2100.0, 2970.0), flatten.map(quad.bottomRight))
        assertPoint(Point2(0.0, 2970.0), flatten.map(quad.bottomLeft))
    }

    @Test
    fun `flattening undoes the camera's perspective for points inside the page`() {
        // Photograph the sheet's centre and a point a quarter of the way in; after
        // flattening they must land where they are on the real sheet.
        val tiltX = 0.4
        val tiltY = 0.25
        val quad = photograph(210.0, 297.0, tiltX, tiltY)
        val flatten = Homography.quadToRectangle(quad, 210.0, 297.0)!!
        val quarter = photograph(105.0, 148.5, tiltX, tiltY) // corners at ±¼ of the sheet

        assertPoint(Point2(105.0, 148.5), flatten.map(Point2(imageWidth / 2, imageHeight / 2)), tolerance = 1e-6)
        assertPoint(Point2(52.5, 74.25), flatten.map(quarter.topLeft), tolerance = 1e-6)
        assertPoint(Point2(157.5, 222.75), flatten.map(quarter.bottomRight), tolerance = 1e-6)
    }

    @Test
    fun `mapping there and back returns the starting point`() {
        val from = Quad(Point2(12.0, 30.0), Point2(410.0, 55.0), Point2(380.0, 520.0), Point2(40.0, 480.0))
        val to = Quad(Point2(0.0, 0.0), Point2(300.0, 0.0), Point2(300.0, 400.0), Point2(0.0, 400.0))
        val forward = Homography.between(from, to)!!
        val back = Homography.between(to, from)!!

        val point = Point2(200.0, 260.0)
        assertPoint(point, back.map(forward.map(point)), tolerance = 1e-6)
    }

    @Test
    fun `a collapsed outline has no transform`() {
        val line = Quad(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(20.0, 0.0), Point2(30.0, 0.0))

        assertNull(Homography.quadToRectangle(line, 100.0, 100.0))
        assertNotNull(Homography.quadToRectangle(line.withCorner(2, Point2(20.0, 30.0)).withCorner(3, Point2(0.0, 30.0)), 100.0, 100.0))
    }

    private fun assertPoint(expected: Point2, actual: Point2, tolerance: Double = 1e-6) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }
}
