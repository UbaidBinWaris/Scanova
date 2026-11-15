package app.scanova.feature.scan.filter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentsTest {

    private fun apply(matrix: FloatArray, value: Int) = ColorMatrices.apply(matrix, value, value, value)[0]

    // ── Strength ────────────────────────────────────────────────────────

    @Test
    fun `strength zero keeps the unfiltered page and one gives the filtered page`() {
        assertEquals(200, Adjustments.blend(unfiltered = 200, filtered = 40, strength = 0f))
        assertEquals(40, Adjustments.blend(unfiltered = 200, filtered = 40, strength = 1f))
    }

    @Test
    fun `strength mixes linearly`() {
        assertEquals(120, Adjustments.blend(unfiltered = 200, filtered = 40, strength = 0.5f))
        assertEquals(80, Adjustments.blend(unfiltered = 200, filtered = 40, strength = 0.75f))
    }

    @Test
    fun `strength is an opacity, clamped`() {
        assertEquals(0, Adjustments.strengthAlpha(-0.3f))
        assertEquals(128, Adjustments.strengthAlpha(0.5f))
        assertEquals(255, Adjustments.strengthAlpha(1f))
        assertEquals(255, Adjustments.strengthAlpha(1.7f))
        assertTrue(Adjustments.isFullStrength(0.999f))
        assertTrue(!Adjustments.isFullStrength(0.99f))
    }

    // ── Brightness and contrast ─────────────────────────────────────────

    @Test
    fun `neutral adjustments change nothing`() {
        val matrix = Adjustments.brightnessContrast(0f, 0f)
        assertArrayEquals(ColorMatrices.IDENTITY, matrix, 1e-6f)
        for (value in listOf(0, 37, 128, 254, 255)) assertEquals(value, apply(matrix, value))
        assertTrue(Adjustments.isNeutral(0f, 0f))
        assertTrue(!Adjustments.isNeutral(0.1f, 0f))
    }

    @Test
    fun `brightness shifts every level by the same amount`() {
        val brighter = Adjustments.brightnessContrast(0.5f, 0f)
        assertEquals(100 + 48, apply(brighter, 100))
        assertEquals(255, apply(brighter, 240))
        val darker = Adjustments.brightnessContrast(-1f, 0f)
        assertEquals(4, apply(darker, 100))
    }

    @Test
    fun `contrast spreads levels around mid grey`() {
        val more = Adjustments.brightnessContrast(0f, 1f)
        assertEquals(2f, Adjustments.contrastFactor(1f), 1e-6f)
        assertEquals(0.5f, Adjustments.contrastFactor(-1f), 1e-6f)
        // Mid grey (127.5) stays, the rest moves away from it.
        assertEquals(127, apply(more, 127))
        assertEquals(55, apply(more, 91))
        assertEquals(201, apply(more, 164))
        val less = Adjustments.brightnessContrast(0f, -1f)
        assertEquals(109, apply(less, 91))
    }

    @Test
    fun `controls are clamped to their range`() {
        assertArrayEquals(Adjustments.brightnessContrast(1f, 1f), Adjustments.brightnessContrast(3f, 5f), 1e-6f)
    }

    // ── Colour matrices ─────────────────────────────────────────────────

    @Test
    fun `chaining applies matrices left to right`() {
        val first = ColorMatrices.brightness(20f)
        val second = ColorMatrices.contrast(2f)
        val chained = ColorMatrices.then(first, second)
        // contrast(100 + 20) = 2 * (120 - 127.5) + 127.5 = 112.5
        assertEquals(113, apply(chained, 100))
        assertEquals(apply(second, apply(first, 100)), apply(chained, 100))
    }

    @Test
    fun `zero saturation is grey and one changes nothing`() {
        val grey = ColorMatrices.apply(ColorMatrices.saturation(0f), 200, 40, 10)
        assertEquals(grey[0], grey[1])
        assertEquals(grey[1], grey[2])
        assertArrayEquals(intArrayOf(200, 40, 10), ColorMatrices.apply(ColorMatrices.saturation(1f), 200, 40, 10))
    }

    @Test
    fun `colour filters keep white paper light`() {
        listOf(ColorMatrices.VIVID, ColorMatrices.WARM, ColorMatrices.COOL, ColorMatrices.VINTAGE).forEach { matrix ->
            ColorMatrices.apply(matrix, 250, 250, 250).forEach { assertTrue("$it", it >= 215) }
        }
    }

    @Test
    fun `warm adds red and cool adds blue`() {
        val warm = ColorMatrices.apply(ColorMatrices.WARM, 128, 128, 128)
        assertTrue(warm[0] > warm[2])
        val cool = ColorMatrices.apply(ColorMatrices.COOL, 128, 128, 128)
        assertTrue(cool[2] > cool[0])
    }

    // ── Tone curves ─────────────────────────────────────────────────────

    @Test
    fun `tone curves never reverse the order of levels`() {
        listOf(
            ToneCurves.gamma(0.72),
            ToneCurves.gamma(1.4),
            ToneCurves.levels(24, 232, 1.12),
            ToneCurves.inkSaver(),
        ).forEach { curve ->
            assertEquals(256, curve.size)
            for (level in 1 until 256) assertTrue(curve[level] >= curve[level - 1])
        }
    }

    @Test
    fun `gamma keeps black and white and lifts the middle`() {
        val lighten = ToneCurves.gamma(0.72)
        assertEquals(0, lighten[0])
        assertEquals(255, lighten[255])
        assertTrue(lighten[128] > 128)
    }

    @Test
    fun `levels clip below black and above white`() {
        val curve = ToneCurves.levels(black = 24, white = 232)
        assertEquals(0, curve[10])
        assertEquals(255, curve[240])
        assertEquals(128, curve[128])
    }

    @Test
    fun `ink saver makes paper white and prints black lighter`() {
        val curve = ToneCurves.inkSaver(paper = 228, inkDarkness = 0.55)
        assertEquals(255, curve[230])
        assertEquals(115, curve[0])
    }
}
