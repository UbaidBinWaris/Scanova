package app.scanova.feature.scan.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAndSteadinessTest {

    private fun quad(offset: Double = 0.0) = Quad(
        Point2(0.1 + offset, 0.1),
        Point2(0.9 + offset, 0.1),
        Point2(0.9 + offset, 0.9),
        Point2(0.1 + offset, 0.9),
    )

    // ── View mapping ────────────────────────────────────────────────────

    @Test
    fun `a portrait image is pillar-boxed in a wider view`() {
        val drawn = FrameMapping.fitInside(imageWidth = 300.0, imageHeight = 400.0, viewWidth = 1000.0, viewHeight = 800.0)

        assertEquals(RectD(left = 200.0, top = 0.0, width = 600.0, height = 800.0), drawn)
    }

    @Test
    fun `a landscape image is letter-boxed in a taller view`() {
        val drawn = FrameMapping.fitInside(imageWidth = 400.0, imageHeight = 300.0, viewWidth = 800.0, viewHeight = 1000.0)

        assertEquals(RectD(left = 0.0, top = 200.0, width = 800.0, height = 600.0), drawn)
    }

    @Test
    fun `empty sizes do not divide by zero`() {
        assertEquals(RectD(0.0, 0.0, 0.0, 0.0), FrameMapping.fitInside(0.0, 400.0, 800.0, 1000.0))
        assertEquals(Point2(0.0, 0.0), FrameMapping.toNormalized(Point2(5.0, 5.0), RectD(0.0, 0.0, 0.0, 0.0)))
    }

    @Test
    fun `view and image coordinates convert both ways`() {
        val drawn = RectD(left = 200.0, top = 0.0, width = 600.0, height = 800.0)

        assertEquals(Point2(500.0, 400.0), FrameMapping.toView(Point2(0.5, 0.5), drawn))
        assertEquals(Point2(0.25, 0.75), FrameMapping.toNormalized(Point2(350.0, 600.0), drawn))
        // A finger outside the image is pulled back to its edge.
        assertEquals(Point2(0.0, 1.0), FrameMapping.toNormalized(Point2(10.0, 900.0), drawn))
    }

    @Test
    fun `sensor coordinates rotate to upright coordinates`() {
        // A point near the sensor frame's top-left corner…
        val point = Point2(0.1, 0.2)

        assertEquals(point, FrameMapping.rotateNormalized(point, 0))
        // …is near the top-right once the frame is turned 90° clockwise.
        assertPoint(Point2(0.8, 0.1), FrameMapping.rotateNormalized(point, 90))
        assertPoint(Point2(0.9, 0.8), FrameMapping.rotateNormalized(point, 180))
        assertPoint(Point2(0.2, 0.9), FrameMapping.rotateNormalized(point, 270))
        assertEquals(point, FrameMapping.rotateNormalized(point, 360))
        assertPoint(Point2(0.2, 0.9), FrameMapping.rotateNormalized(point, -90))
    }

    @Test
    fun `four quarter turns bring a point back`() {
        var point = Point2(0.3, 0.7)
        repeat(4) { point = FrameMapping.rotateNormalized(point, 90) }

        assertPoint(Point2(0.3, 0.7), point)
    }

    private fun assertPoint(expected: Point2, actual: Point2) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }

    // ── Auto-capture ────────────────────────────────────────────────────

    @Test
    fun `a page held still triggers exactly one capture`() {
        val tracker = SteadinessTracker(requiredStableMillis = 900)
        val captures = (0..2000 step 100).count { time -> tracker.onFrame(quad(), time.toLong()).shouldCapture }

        assertEquals(1, captures)
    }

    @Test
    fun `progress grows while the page is still and completes on time`() {
        val tracker = SteadinessTracker(requiredStableMillis = 1000)

        assertEquals(0f, tracker.onFrame(quad(), 0).progress, 0f)
        assertEquals(0.5f, tracker.onFrame(quad(), 500).progress, 1e-6f)
        assertFalse(tracker.onFrame(quad(), 900).shouldCapture)
        val done = tracker.onFrame(quad(), 1000)
        assertEquals(1f, done.progress, 0f)
        assertTrue(done.shouldCapture)
    }

    @Test
    fun `small hand tremor is tolerated`() {
        val tracker = SteadinessTracker(requiredStableMillis = 900, tolerance = 0.025)
        var captured = false
        for (frame in 0..12) {
            val tremor = if (frame % 2 == 0) 0.01 else -0.01
            captured = captured || tracker.onFrame(quad(tremor), frame * 100L).shouldCapture
        }

        assertTrue(captured)
    }

    @Test
    fun `moving the page restarts the wait`() {
        val tracker = SteadinessTracker(requiredStableMillis = 900)
        tracker.onFrame(quad(), 0)
        tracker.onFrame(quad(), 800)

        // The page slides by 10 % of the frame just before the capture would fire.
        val moved = tracker.onFrame(quad(0.1), 850)
        assertEquals(0f, moved.progress, 0f)
        assertFalse(tracker.onFrame(quad(0.1), 1000).shouldCapture)
        assertFalse(tracker.onFrame(quad(0.1), 1700).shouldCapture)
        assertTrue(tracker.onFrame(quad(0.1), 1750).shouldCapture)
    }

    @Test
    fun `a page that keeps moving is never captured`() {
        val tracker = SteadinessTracker(requiredStableMillis = 900)
        val captures = (0..40).count { frame -> tracker.onFrame(quad(frame * 0.03 % 0.09), frame * 100L).shouldCapture }

        assertEquals(0, captures)
    }

    @Test
    fun `a brief detection dropout is forgiven but a lost page is not`() {
        val forgiving = SteadinessTracker(requiredStableMillis = 900, maxMissedFrames = 2)
        forgiving.onFrame(quad(), 0)
        forgiving.onFrame(null, 100)
        forgiving.onFrame(null, 200)
        forgiving.onFrame(quad(), 300)
        assertTrue(forgiving.onFrame(quad(), 900).shouldCapture)

        val lost = SteadinessTracker(requiredStableMillis = 900, maxMissedFrames = 2)
        lost.onFrame(quad(), 0)
        repeat(3) { lost.onFrame(null, 100L * (it + 1)) }
        assertEquals(0f, lost.onFrame(null, 500).progress, 0f)
        // Timing starts again when the page reappears.
        assertFalse(lost.onFrame(quad(), 900).shouldCapture)
        assertTrue(lost.onFrame(quad(), 1800).shouldCapture)
    }

    @Test
    fun `no page means no capture`() {
        val tracker = SteadinessTracker()

        assertFalse((0..3000 step 100).any { tracker.onFrame(null, it.toLong()).shouldCapture })
    }

    @Test
    fun `after a reset the same page can be captured again`() {
        val tracker = SteadinessTracker(requiredStableMillis = 500)
        tracker.onFrame(quad(), 0)
        assertTrue(tracker.onFrame(quad(), 500).shouldCapture)
        assertFalse(tracker.onFrame(quad(), 600).shouldCapture)

        tracker.reset()
        tracker.onFrame(quad(), 700)
        assertTrue(tracker.onFrame(quad(), 1200).shouldCapture)
    }

    // ── Overlay smoothing ───────────────────────────────────────────────

    @Test
    fun `smoothing moves part of the way and snaps on a big jump`() {
        val blended = QuadSmoother.blend(quad(), quad(0.04), weight = 0.5)
        assertEquals(0.12, blended.topLeft.x, 1e-9)

        assertEquals(quad(0.3), QuadSmoother.blend(quad(), quad(0.3)))
        assertEquals(quad(0.04), QuadSmoother.blend(null, quad(0.04)))
    }
}
