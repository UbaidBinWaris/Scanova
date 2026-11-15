package app.scanova.feature.scan.idcard

import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.geometry.RectD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardLayoutTest {

    private val page = IdCardLayout.pageSize(2400)

    private fun RectD.right() = left + width
    private fun RectD.bottom() = top + height

    @Test
    fun `the page has A4 proportions`() {
        assertEquals(2400, page.height)
        assertEquals(210.0 / 297.0, page.width.toDouble() / page.height, 0.001)
    }

    @Test
    fun `a card is placed at its real size`() {
        val slot = IdCardLayout.place(page.width, page.height, IdCardLayout.CARD_ASPECT, upper = true)
        // 85.6 mm of a 210 mm wide page.
        assertEquals(page.width * 85.6 / 210.0, slot.width, 1e-6)
        assertEquals(slot.width / IdCardLayout.CARD_ASPECT, slot.height, 1e-6)
    }

    @Test
    fun `front goes in the upper half and back in the lower half, both centred`() {
        val front = IdCardLayout.place(page.width, page.height, IdCardLayout.CARD_ASPECT, upper = true)
        val back = IdCardLayout.place(page.width, page.height, IdCardLayout.CARD_ASPECT, upper = false)
        val half = page.height / 2.0
        assertEquals(page.width / 2.0, front.left + front.width / 2.0, 1e-6)
        assertEquals(half / 2.0, front.top + front.height / 2.0, 1e-6)
        assertEquals(page.width / 2.0, back.left + back.width / 2.0, 1e-6)
        assertEquals(half + half / 2.0, back.top + back.height / 2.0, 1e-6)
        assertTrue(front.bottom() < half)
        assertTrue(back.top > half)
    }

    @Test
    fun `cards stay inside the margins`() {
        val margin = page.width * IdCardLayout.MARGIN_FRACTION
        listOf(true, false).forEach { upper ->
            listOf(IdCardLayout.CARD_ASPECT, 1.0 / IdCardLayout.CARD_ASPECT, 4.0, 0.25).forEach { aspect ->
                val slot = IdCardLayout.place(page.width, page.height, aspect, upper)
                val halfTop = if (upper) 0.0 else page.height / 2.0
                assertTrue(slot.left >= margin - 1e-6)
                assertTrue(slot.right() <= page.width - margin + 1e-6)
                assertTrue(slot.top >= halfTop + margin - 1e-6)
                assertTrue(slot.bottom() <= halfTop + page.height / 2.0 - margin + 1e-6)
                // The side keeps its own proportions.
                assertEquals(aspect, slot.width / slot.height, 1e-6)
            }
        }
    }

    @Test
    fun `an upright card keeps its long side as long as a landscape one`() {
        val landscape = IdCardLayout.place(page.width, page.height, IdCardLayout.CARD_ASPECT, upper = true)
        val portrait = IdCardLayout.place(page.width, page.height, 1.0 / IdCardLayout.CARD_ASPECT, upper = true)
        assertEquals(landscape.width, portrait.height, 1e-6)
        assertEquals(landscape.height, portrait.width, 1e-6)
    }

    @Test
    fun `the guide has the shape of a card on screen`() {
        for (frameAspect in listOf(3.0 / 4.0, 9.0 / 16.0, 4.0 / 3.0)) {
            val guide = IdCardLayout.guide(frameAspect)
            val widthPx = (guide.topRight.x - guide.topLeft.x) * frameAspect
            val heightPx = guide.bottomLeft.y - guide.topLeft.y
            assertEquals(IdCardLayout.CARD_ASPECT, widthPx / heightPx, 1e-9)
            // Centred, inside the frame.
            assertEquals(0.5, (guide.topLeft.x + guide.topRight.x) / 2.0, 1e-9)
            assertEquals(0.5, (guide.topLeft.y + guide.bottomLeft.y) / 2.0, 1e-9)
            guide.corners.forEach { assertTrue(it.x in 0.0..1.0 && it.y in 0.0..1.0) }
        }
    }

    @Test
    fun `only an outline that sits in the guide counts as the card`() {
        val guide = IdCardLayout.guide(3.0 / 4.0)
        val shifted = guide.map { Point2(it.x + 0.02, it.y - 0.01) }
        assertTrue(IdCardLayout.matchesGuide(shifted, guide))
        val tiny = Quad.inset(1.0, 1.0, 0.45)
        assertFalse(IdCardLayout.matchesGuide(tiny, guide))
        val wholeFrame = Quad.inset(1.0, 1.0, 0.0)
        assertFalse(IdCardLayout.matchesGuide(wholeFrame, guide))
        val offToTheSide = guide.map { Point2(it.x, it.y + 0.4) }
        assertFalse(IdCardLayout.matchesGuide(offToTheSide, guide))
    }

    @Test
    fun `card shapes are recognised either way round`() {
        assertTrue(IdCardLayout.looksLikeCard(IdCardLayout.CARD_ASPECT))
        assertTrue(IdCardLayout.looksLikeCard(1.0 / IdCardLayout.CARD_ASPECT))
        assertFalse(IdCardLayout.looksLikeCard(210.0 / 297.0 * 0.5))
        assertEquals(IdCardLayout.CARD_ASPECT, IdCardLayout.aspectOf(0, 10), 1e-9)
    }
}
