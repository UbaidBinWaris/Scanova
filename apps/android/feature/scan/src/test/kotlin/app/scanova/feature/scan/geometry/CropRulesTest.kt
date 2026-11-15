package app.scanova.feature.scan.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropRulesTest {

    private val outline = Quad(Point2(0.1, 0.1), Point2(0.9, 0.1), Point2(0.9, 0.9), Point2(0.1, 0.9))

    @Test
    fun `a corner moves where it is dragged`() {
        val moved = CropRules.moveCorner(outline, 0, Point2(0.2, 0.15))
        assertEquals(Point2(0.2, 0.15), moved?.topLeft)
        assertEquals(outline.bottomRight, moved?.bottomRight)
    }

    @Test
    fun `corners stay on the image`() {
        val moved = CropRules.moveCorner(outline, 2, Point2(1.4, -0.2 + 1.5))
        assertEquals(Point2(1.0, 1.0), moved?.bottomRight)
    }

    @Test
    fun `a corner dragged past its neighbours is refused`() {
        // The top-left corner dragged beyond the bottom-right one folds the page over itself.
        assertNull(CropRules.moveCorner(outline, 0, Point2(0.95, 0.95)))
    }

    @Test
    fun `a page too small to use is refused`() {
        val small = Quad(Point2(0.4, 0.4), Point2(0.45, 0.4), Point2(0.45, 0.45), Point2(0.4, 0.45))
        assertNull(CropRules.moveCorner(small, 2, Point2(0.42, 0.42)))
    }

    @Test
    fun `the whole image is recognised`() {
        assertTrue(CropRules.isWholeImage(Quad.inset(1.0, 1.0, 0.0)))
        assertTrue(CropRules.isWholeImage(Quad.inset(1.0, 1.0, 0.0005)))
        assertFalse(CropRules.isWholeImage(outline))
    }
}
