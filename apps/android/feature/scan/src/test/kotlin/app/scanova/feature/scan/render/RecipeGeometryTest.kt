package app.scanova.feature.scan.render

import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PagePoint
import app.scanova.feature.scan.geometry.Homography
import app.scanova.feature.scan.geometry.PixelSize
import app.scanova.feature.scan.geometry.Point2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeGeometryTest {

    private fun assertNear(expected: Point2, actual: Point2, tolerance: Double = 1e-6) {
        assertEquals("x", expected.x, actual.x, tolerance)
        assertEquals("y", expected.y, actual.y, tolerance)
    }

    /** Where pixel [rawPoint] of the original lands on the finished page. */
    private fun mapped(plan: WarpPlan, rawPoint: Point2): Point2 {
        val transform = Homography.quadToRectangle(plan.source, plan.width.toDouble(), plan.height.toDouble())
        assertNotNull(transform)
        return transform!!.map(rawPoint)
    }

    private fun crop(vararg points: Pair<Number, Number>) = points.map { PagePoint(it.first.toFloat(), it.second.toFloat()) }

    // ── Turns ───────────────────────────────────────────────────────────

    @Test
    fun `degrees snap to the nearest quarter turn`() {
        assertEquals(0, RecipeGeometry.normalizeDegrees(0))
        assertEquals(90, RecipeGeometry.normalizeDegrees(90))
        assertEquals(270, RecipeGeometry.normalizeDegrees(-90))
        assertEquals(0, RecipeGeometry.normalizeDegrees(360))
        assertEquals(180, RecipeGeometry.normalizeDegrees(-180))
        assertEquals(90, RecipeGeometry.normalizeDegrees(80))
        assertEquals(90, RecipeGeometry.normalizeDegrees(450))
    }

    @Test
    fun `turning left and right adds quarter turns`() {
        assertEquals(90, RecipeGeometry.turn(0, 1))
        assertEquals(270, RecipeGeometry.turn(0, -1))
        assertEquals(0, RecipeGeometry.turn(270, 1))
        assertEquals(180, RecipeGeometry.turn(90, 1))
    }

    @Test
    fun `sideways turns swap width and height`() {
        assertEquals(PixelSize(400, 300), RecipeGeometry.turnedSize(400, 300, 0))
        assertEquals(PixelSize(300, 400), RecipeGeometry.turnedSize(400, 300, 90))
        assertEquals(PixelSize(400, 300), RecipeGeometry.turnedSize(400, 300, 180))
        assertEquals(PixelSize(300, 400), RecipeGeometry.turnedSize(400, 300, 270))
    }

    @Test
    fun `upright and raw fractions convert both ways for every source rotation`() {
        val points = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(0.25, 0.75), Point2(0.6, 0.1))
        for (rotation in listOf(0, 90, 180, 270)) {
            for (point in points) {
                assertNear(point, RecipeGeometry.rawToUpright(RecipeGeometry.uprightToRaw(point, rotation), rotation))
            }
        }
    }

    @Test
    fun `the upright top left comes from the right corner of the original`() {
        // A photo that must turn 90° clockwise to be upright: its bottom-left corner becomes the top-left.
        assertNear(Point2(0.0, 1.0), RecipeGeometry.uprightToRaw(Point2(0.0, 0.0), 90))
        // Turning 270° clockwise: the top-right corner becomes the top-left.
        assertNear(Point2(1.0, 0.0), RecipeGeometry.uprightToRaw(Point2(0.0, 0.0), 270))
        assertNear(Point2(1.0, 1.0), RecipeGeometry.uprightToRaw(Point2(0.0, 0.0), 180))
    }

    // ── Crop outlines ───────────────────────────────────────────────────

    @Test
    fun `a missing or unusable crop means the whole image`() {
        assertNull(RecipeGeometry.cropQuad(null))
        assertNull(RecipeGeometry.cropQuad(crop(0 to 0, 1 to 0, 1 to 1)))
        // Corners in the wrong order fold the outline over itself.
        assertNull(RecipeGeometry.cropQuad(crop(0 to 0, 1 to 1, 1 to 0, 0 to 1)))
        assertNotNull(RecipeGeometry.cropQuad(crop(0.1 to 0.1, 0.9 to 0.1, 0.9 to 0.9, 0.1 to 0.9)))
    }

    @Test
    fun `crop outlines survive a round trip`() {
        val quad = RecipeGeometry.cropQuad(crop(0.1 to 0.2, 0.9 to 0.1, 0.8 to 0.9, 0.2 to 0.8))!!
        assertEquals(quad, RecipeGeometry.cropQuad(RecipeGeometry.toCrop(quad)))
    }

    // ── Warp plans ──────────────────────────────────────────────────────

    @Test
    fun `whole image without turns is the image itself`() {
        val plan = RecipeGeometry.plan(400, 300, PageEdit(), maxLongEdge = 1000)
        assertEquals(400, plan.width)
        assertEquals(300, plan.height)
        assertNear(Point2(0.0, 0.0), mapped(plan, Point2(0.0, 0.0)))
        assertNear(Point2(400.0, 300.0), mapped(plan, Point2(400.0, 300.0)))
    }

    @Test
    fun `whole image is only ever scaled down`() {
        val plan = RecipeGeometry.plan(4000, 3000, PageEdit(), maxLongEdge = 2000)
        assertEquals(2000, plan.width)
        assertEquals(1500, plan.height)
    }

    @Test
    fun `the source rotation turns the original upright`() {
        // Sensor image 400 × 300 that is upright once turned 90° clockwise.
        val plan = RecipeGeometry.plan(400, 300, PageEdit(sourceRotation = 90), maxLongEdge = 1000)
        assertEquals(300, plan.width)
        assertEquals(400, plan.height)
        // Turning clockwise moves the original's bottom-left corner to the top-left…
        assertNear(Point2(0.0, 0.0), mapped(plan, Point2(0.0, 300.0)))
        // …and its top-left corner to the top-right.
        assertNear(Point2(300.0, 0.0), mapped(plan, Point2(0.0, 0.0)))
        assertNear(Point2(300.0, 400.0), mapped(plan, Point2(400.0, 0.0)))
    }

    @Test
    fun `the user's rotation turns the flattened page`() {
        val plan = RecipeGeometry.plan(400, 300, PageEdit(rotation = 90), maxLongEdge = 1000)
        assertEquals(300, plan.width)
        assertEquals(400, plan.height)
        assertNear(Point2(300.0, 0.0), mapped(plan, Point2(0.0, 0.0)))
        assertNear(Point2(0.0, 0.0), mapped(plan, Point2(0.0, 300.0)))

        val upsideDown = RecipeGeometry.plan(400, 300, PageEdit(rotation = 180), maxLongEdge = 1000)
        assertNear(Point2(400.0, 300.0), mapped(upsideDown, Point2(0.0, 0.0)))
    }

    @Test
    fun `source rotation and user rotation add up`() {
        // 90° to make it upright, then the user turns it back 90°: the original as it was stored.
        val plan = RecipeGeometry.plan(400, 300, PageEdit(sourceRotation = 90, rotation = 270), maxLongEdge = 1000)
        assertEquals(400, plan.width)
        assertEquals(300, plan.height)
        assertNear(Point2(0.0, 0.0), mapped(plan, Point2(0.0, 0.0)))
        assertNear(Point2(400.0, 300.0), mapped(plan, Point2(400.0, 300.0)))
    }

    @Test
    fun `crop fractions are of the upright image, whatever the source rotation`() {
        // Keep the upright image's top half.
        val topHalf = crop(0 to 0, 1 to 0, 1 to 0.5, 0 to 0.5)
        val plan = RecipeGeometry.plan(400, 300, PageEdit(sourceRotation = 90, crop = topHalf), maxLongEdge = 1000)
        // Upright the image is 300 × 400, its top half 300 × 200.
        assertEquals(300.0 / 200.0, plan.width.toDouble() / plan.height, 0.02)
        // The upright top half is the left half of the original (x 0..200), whose bottom-left is the page's top-left.
        assertNear(Point2(0.0, 0.0), mapped(plan, Point2(0.0, 300.0)), tolerance = 1e-3)
        assertNear(Point2(plan.width.toDouble(), plan.height.toDouble()), mapped(plan, Point2(200.0, 0.0)), tolerance = 1e-3)
    }

    @Test
    fun `a cropped page keeps its resolution and the longest side limit`() {
        val inner = crop(0.25 to 0.25, 0.75 to 0.25, 0.75 to 0.75, 0.25 to 0.75)
        val plan = RecipeGeometry.plan(4000, 3000, PageEdit(crop = inner), maxLongEdge = 5000)
        assertEquals(2000, plan.width)
        assertEquals(1500, plan.height)
        val limited = RecipeGeometry.plan(4000, 3000, PageEdit(crop = inner), maxLongEdge = 1000)
        assertEquals(1000, maxOf(limited.width, limited.height))
    }
}
