package app.scanova.feature.scan.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadTest {

    private fun p(x: Number, y: Number) = Point2(x.toDouble(), y.toDouble())

    private val square = Quad(p(10, 10), p(110, 10), p(110, 110), p(10, 110))

    // ── Corner ordering ─────────────────────────────────────────────────

    @Test
    fun `corners are named the same whatever order they arrive in`() {
        val points = listOf(p(110, 110), p(10, 10), p(10, 110), p(110, 10))

        // All 24 orderings of the same four points.
        for (a in 0..3) for (b in 0..3) for (c in 0..3) for (d in 0..3) {
            if (setOf(a, b, c, d).size != 4) continue
            assertEquals(square, Quad.fromUnordered(listOf(points[a], points[b], points[c], points[d])))
        }
    }

    @Test
    fun `a tilted page keeps a clockwise order starting near the top left`() {
        // A page rotated about 20 degrees clockwise.
        val quad = Quad.fromUnordered(listOf(p(300, 420), p(120, 60), p(60, 330), p(360, 150)))!!

        assertEquals(p(120, 60), quad.topLeft)
        assertEquals(p(360, 150), quad.topRight)
        assertEquals(p(300, 420), quad.bottomRight)
        assertEquals(p(60, 330), quad.bottomLeft)
        assertTrue(quad.isConvex())
    }

    @Test
    fun `a page seen in perspective is ordered correctly`() {
        // Far edge (top) shorter than near edge (bottom).
        val quad = Quad.fromUnordered(listOf(p(40, 400), p(420, 380), p(330, 90), p(130, 100)))!!

        assertEquals(p(130, 100), quad.topLeft)
        assertEquals(p(330, 90), quad.topRight)
        assertEquals(p(420, 380), quad.bottomRight)
        assertEquals(p(40, 400), quad.bottomLeft)
    }

    @Test
    fun `unusable point sets are refused`() {
        assertNull(Quad.fromUnordered(listOf(p(0, 0), p(1, 1), p(2, 2))))
        assertNull(Quad.fromUnordered(listOf(p(0, 0), p(1, 0), p(2, 0), p(3, 0), p(4, 0))))
        // Four points on one line.
        assertNull(Quad.fromUnordered(listOf(p(0, 0), p(10, 0), p(20, 0), p(30, 0))))
        // Two identical points.
        assertNull(Quad.fromUnordered(listOf(p(0, 0), p(0, 0), p(10, 10), p(0, 10))))
        // A dart: one corner pokes inwards.
        assertNull(Quad.fromUnordered(listOf(p(0, 0), p(100, 0), p(50, 20), p(50, 100))))
    }

    // ── Measurements ────────────────────────────────────────────────────

    @Test
    fun `area and side lengths`() {
        assertEquals(10_000.0, square.area(), 1e-9)
        assertEquals(100.0, square.topLength, 1e-9)
        assertEquals(100.0, square.leftLength, 1e-9)
        val trapezoid = Quad(p(20, 0), p(80, 0), p(100, 50), p(0, 50))
        assertEquals((60 + 100) / 2.0 * 50, trapezoid.area(), 1e-9)
    }

    @Test
    fun `convexity detects crossed and folded outlines`() {
        assertTrue(square.isConvex())
        // "Bow tie": top-right and bottom-right swapped.
        assertFalse(Quad(p(10, 10), p(110, 110), p(110, 10), p(10, 110)).isConvex())
        // Three corners on one line.
        assertFalse(Quad(p(0, 0), p(50, 0), p(100, 0), p(0, 100)).isConvex())
    }

    @Test
    fun `interior angles of a rectangle are right angles`() {
        square.interiorAngles().forEach { assertEquals(90.0, it, 1e-9) }
        val angles = Quad(p(0, 0), p(100, 0), p(140, 100), p(40, 100)).interiorAngles()
        assertEquals(360.0, angles.sum(), 1e-6)
        assertEquals(angles[0], angles[2], 1e-6)
    }

    @Test
    fun `scale, clamp and corner replacement`() {
        val unit = Quad(p(0.1, 0.2), p(0.9, 0.2), p(0.9, 0.8), p(0.1, 0.8))
        assertEquals(Quad(p(100, 100), p(900, 100), p(900, 400), p(100, 400)), unit.scale(1000.0, 500.0))
        assertEquals(p(0, 0), Quad(p(-5, -5), p(50, 0), p(50, 50), p(0, 50)).clampTo(40.0, 40.0).topLeft)
        assertEquals(p(40, 40), Quad(p(0, 0), p(50, 0), p(90, 90), p(0, 50)).clampTo(40.0, 40.0).bottomRight)
        assertEquals(p(1, 2), square.withCorner(2, p(1, 2)).bottomRight)
        assertEquals(square.topLeft, square.withCorner(2, p(1, 2)).topLeft)
        assertEquals(5.0, square.maxCornerDistance(square.withCorner(3, p(13, 114))), 1e-9)
    }

    // ── Scoring ─────────────────────────────────────────────────────────

    private val frameW = 640.0
    private val frameH = 480.0

    @Test
    fun `a page filling most of the frame scores high`() {
        val page = Quad(p(60, 40), p(580, 50), p(590, 440), p(50, 430))

        assertTrue(QuadScorer.score(page, frameW, frameH) > 0.8)
    }

    @Test
    fun `a larger page beats a smaller one of the same shape`() {
        val large = Quad(p(60, 40), p(580, 40), p(580, 440), p(60, 440))
        val small = Quad(p(220, 160), p(420, 160), p(420, 320), p(220, 320))

        assertTrue(QuadScorer.score(large, frameW, frameH) > QuadScorer.score(small, frameW, frameH))
        assertTrue(QuadScorer.score(small, frameW, frameH) > 0.0)
    }

    @Test
    fun `a clean rectangle beats a skewed outline of similar area`() {
        val rectangle = Quad(p(100, 80), p(540, 80), p(540, 400), p(100, 400))
        val skewed = Quad(p(100, 80), p(540, 150), p(600, 400), p(40, 330))

        assertTrue(QuadScorer.score(rectangle, frameW, frameH) > QuadScorer.score(skewed, frameW, frameH))
    }

    @Test
    fun `things that are not pages are rejected`() {
        // Too small: a business card far away, a text block.
        assertEquals(0.0, QuadScorer.score(Quad(p(300, 220), p(360, 220), p(360, 260), p(300, 260)), frameW, frameH), 0.0)
        // A sliver: a table edge or a shadow.
        assertEquals(0.0, QuadScorer.score(Quad(p(20, 200), p(620, 190), p(620, 420), p(600, 425)), frameW, frameH), 0.0)
        // Very acute corners.
        assertEquals(0.0, QuadScorer.score(Quad(p(20, 240), p(320, 200), p(620, 240), p(320, 280)), frameW, frameH), 0.0)
        // Opposite sides wildly different: a triangle-like shape.
        assertEquals(0.0, QuadScorer.score(Quad(p(300, 40), p(340, 40), p(620, 440), p(20, 440)), frameW, frameH), 0.0)
        // Self-crossing.
        assertEquals(0.0, QuadScorer.score(Quad(p(60, 40), p(580, 440), p(580, 40), p(60, 440)), frameW, frameH), 0.0)
        // Nonsense frame.
        assertEquals(0.0, QuadScorer.score(square, 0.0, 0.0), 0.0)
    }

    @Test
    fun `score never leaves the unit interval`() {
        val whole = Quad(p(0, 0), p(640, 0), p(640, 480), p(0, 480))
        val score = QuadScorer.score(whole, frameW, frameH)

        assertTrue(score in 0.0..1.0)
        assertNotNull(Quad.fromUnordered(whole.corners))
    }
}
