package app.scanova.feature.scan.render

import app.scanova.core.data.model.MarkupStroke
import app.scanova.core.data.model.MarkupTool
import app.scanova.core.data.model.PagePoint
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.RectD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupGeometryTest {

    private val drawn = RectD(left = 20.0, top = 40.0, width = 300.0, height = 400.0)

    private fun assertNear(expected: PagePoint, actual: PagePoint) {
        assertEquals(expected.x, actual.x, 1e-5f)
        assertEquals(expected.y, actual.y, 1e-5f)
    }

    @Test
    fun `touches become fractions of the page`() {
        assertNear(PagePoint(0f, 0f), MarkupGeometry.toPage(Point2(20.0, 40.0), drawn))
        assertNear(PagePoint(0.5f, 0.25f), MarkupGeometry.toPage(Point2(170.0, 140.0), drawn))
        assertNear(PagePoint(1f, 1f), MarkupGeometry.toPage(Point2(320.0, 440.0), drawn))
    }

    @Test
    fun `touches beside the page stay on its edge`() {
        assertNear(PagePoint(0f, 1f), MarkupGeometry.toPage(Point2(-50.0, 900.0), drawn))
    }

    @Test
    fun `page points land where they were drawn at any size`() {
        val point = MarkupGeometry.toPage(Point2(95.0, 340.0), drawn)
        val back = MarkupGeometry.toView(point, drawn)
        assertEquals(95.0, back.x, 1e-3)
        assertEquals(340.0, back.y, 1e-3)
        // The same point on the full-size page (2400 × 3200) is at the same place relative to the page.
        val full = MarkupGeometry.toView(point, RectD(0.0, 0.0, 2400.0, 3200.0))
        assertEquals(600.0, full.x, 1e-2)
        assertEquals(2400.0, full.y, 1e-2)
    }

    @Test
    fun `widths scale with the page`() {
        assertEquals(6f, MarkupGeometry.widthPx(0.02f, 300f), 1e-4f)
        assertEquals(48f, MarkupGeometry.widthPx(0.02f, 2400f), 1e-4f)
        // Never thinner than a pixel.
        assertEquals(1f, MarkupGeometry.widthPx(0.0001f, 300f), 1e-4f)
    }

    @Test
    fun `strokes turn with the page`() {
        val stroke = MarkupStroke(MarkupTool.PEN, 0xFF000000, width = 0.01f, points = listOf(PagePoint(0.1f, 0.2f), PagePoint(0.9f, 0.2f)))
        // A 300 × 400 page turned a quarter clockwise becomes 400 × 300.
        val turned = MarkupGeometry.turn(listOf(stroke), quarterTurns = 1, pageWidth = 300f, pageHeight = 400f).single()
        assertNear(PagePoint(0.8f, 0.1f), turned.points[0])
        assertNear(PagePoint(0.8f, 0.9f), turned.points[1])
        // 0.01 of 300 px is 3 px; 3 px of the new 400 px width is 0.0075.
        assertEquals(0.0075f, turned.width, 1e-6f)
        assertEquals(stroke.tool, turned.tool)
        assertEquals(stroke.color, turned.color)
    }

    @Test
    fun `turning back restores the strokes`() {
        val stroke = MarkupStroke(MarkupTool.HIGHLIGHTER, 0xFFFFE34D, width = 0.03f, points = listOf(PagePoint(0.3f, 0.6f)))
        val right = MarkupGeometry.turn(listOf(stroke), 1, 300f, 400f)
        val back = MarkupGeometry.turn(right, -1, 400f, 300f).single()
        assertNear(stroke.points.single(), back.points.single())
        assertEquals(stroke.width, back.width, 1e-6f)
        val fullCircle = (1..4).fold(listOf(stroke)) { strokes, turn ->
            val (width, height) = if (turn % 2 == 1) 300f to 400f else 400f to 300f
            MarkupGeometry.turn(strokes, 1, width, height)
        }.single()
        assertNear(stroke.points.single(), fullCircle.points.single())
        assertEquals(stroke.width, fullCircle.width, 1e-6f)
    }

    @Test
    fun `tiny moves are not kept as new points`() {
        val last = PagePoint(0.5f, 0.5f)
        assertTrue(MarkupGeometry.isNewPoint(null, last, pageAspect = 0.75f))
        assertFalse(MarkupGeometry.isNewPoint(last, PagePoint(0.5005f, 0.5f), pageAspect = 0.75f))
        assertTrue(MarkupGeometry.isNewPoint(last, PagePoint(0.51f, 0.5f), pageAspect = 0.75f))
    }

    @Test
    fun `a path goes through its end points and turns a tap into a dot`() {
        val commands = mutableListOf<String>()
        MarkupGeometry.trace(
            points = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(10.0, 10.0)),
            moveTo = { x, y -> commands += "M $x $y" },
            lineTo = { x, y -> commands += "L $x $y" },
            quadTo = { x1, y1, x2, y2 -> commands += "Q $x1 $y1 $x2 $y2" },
        )
        assertEquals(listOf("M 0.0 0.0", "Q 10.0 0.0 10.0 5.0", "L 10.0 10.0"), commands)

        commands.clear()
        MarkupGeometry.trace(
            points = listOf(Point2(4.0, 5.0)),
            moveTo = { x, y -> commands += "M $x $y" },
            lineTo = { x, y -> commands += "L $x $y" },
            quadTo = { _, _, _, _ -> commands += "Q" },
        )
        assertEquals(listOf("M 4.0 5.0", "L 4.0 5.0"), commands)
    }
}
