package app.scanova.core.ml.text

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOrientationTest {

    private val upright = page(
        angle = 0f,
        confidence = 0.92f,
        "INVOICE",
        "Acme Software Solutions",
        "Invoice Date: 03/10/2026",
        "Please pay the total amount due within 30 days",
        "Thank you for your business",
    )

    /** What the recogniser makes of upside-down text: level, unsure, and not words. */
    private val garbled = page(angle = 0f, confidence = 0.31f, "sAep 0E u!4!M anp", "ssau!snq ɹno∧ ɹoɟ", "pjno0 33IOANI")

    @Test
    fun `an upright page is decided by the first pass alone`() = runTest {
        val first = PageOrientation.quality(upright)
        assertTrue(first.confidentlyUpright)
        var probes = 0
        assertEquals(0, PageOrientation.search(first) { probes++; null })
        assertEquals(0, probes)
    }

    @Test
    fun `sideways text is turned the way its lines point first`() = runTest {
        // Lines read at +90°: the page has to turn the other way (270° clockwise).
        val first = PageOrientation.quality(page(angle = 90f, confidence = 0.8f, *upright.lines.map { it.text }.toTypedArray()))
        assertEquals(90, first.tiltedQuadrant)
        assertEquals(270, PageOrientation.candidateTurns(first).first())
        val tried = mutableListOf<Int>()
        val turn = PageOrientation.search(first) { candidate ->
            tried += candidate
            PageOrientation.quality(if (candidate == 270) upright else garbled)
        }
        assertEquals(270, turn)
        assertEquals(listOf(270), tried)
    }

    @Test
    fun `the recogniser's angle running the other way is still found`() = runTest {
        val first = PageOrientation.quality(page(angle = 90f, confidence = 0.8f, *upright.lines.map { it.text }.toTypedArray()))
        val turn = PageOrientation.search(first) { candidate -> PageOrientation.quality(if (candidate == 90) upright else garbled) }
        assertEquals(90, turn)
    }

    @Test
    fun `an upside-down page read as noise is turned round`() = runTest {
        val first = PageOrientation.quality(garbled)
        assertFalse(first.confidentlyUpright)
        val turn = PageOrientation.search(first) { candidate -> PageOrientation.quality(if (candidate == 180) upright else garbled) }
        assertEquals(180, turn)
    }

    @Test
    fun `nothing clearly better keeps the page as it is`() = runTest {
        val first = PageOrientation.quality(garbled)
        assertEquals(0, PageOrientation.search(first) { PageOrientation.quality(garbled) })
        // A little text that reads slightly better is not enough.
        val few = page(angle = 0f, confidence = 0.9f, "No 12")
        assertEquals(0, PageOrientation.search(first) { PageOrientation.quality(few) })
    }

    @Test
    fun `a page without text is never probed`() = runTest {
        var probes = 0
        assertEquals(0, PageOrientation.search(PageOrientation.quality(RecognizedText.EMPTY)) { probes++; null })
        assertEquals(0, probes)
    }

    @Test
    fun `recognition failures during the search keep the page as it is`() = runTest {
        assertEquals(0, PageOrientation.search(PageOrientation.quality(garbled)) { null })
    }

    @Test
    fun `a missing confidence is neutral`() {
        val unknown = PageOrientation.quality(page(angle = 0f, confidence = Float.NaN, *upright.lines.map { it.text }.toTypedArray()))
        assertEquals(PageOrientation.UNKNOWN_CONFIDENCE, unknown.confidence, 0.001f)
        // Real words still make it clearly upright.
        assertTrue(unknown.confidentlyUpright)
    }

    @Test
    fun `angles map to quarter turns with a tolerance`() {
        assertEquals(0, PageOrientation.quadrantOf(3f))
        assertEquals(0, PageOrientation.quadrantOf(-12f))
        assertEquals(90, PageOrientation.quadrantOf(88f))
        assertEquals(270, PageOrientation.quadrantOf(-91f))
        assertEquals(180, PageOrientation.quadrantOf(179f))
        assertEquals(180, PageOrientation.quadrantOf(-178f))
        assertNull(PageOrientation.quadrantOf(45f))
        assertEquals(0, PageOrientation.quadrantOf(Float.NaN))
    }

    private fun page(angle: Float, confidence: Float, vararg lines: String) =
        RecognizedText(lines.map { RecognizedLine(it, angle, confidence) })
}
