package app.scanova.feature.scan.filter

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceMathTest {

    @Test
    fun `smoothstep is 0 below, 1 above and rises smoothly between`() {
        assertEquals(0f, EnhanceMath.smoothstep(0.2f, 0.4f, 0.1f))
        assertEquals(1f, EnhanceMath.smoothstep(0.2f, 0.4f, 0.5f))
        assertEquals(0.5f, EnhanceMath.smoothstep(0.2f, 0.4f, 0.3f), 1e-6f)
        var previous = -1f
        for (step in 0..100) {
            val value = EnhanceMath.smoothstep(0f, 1f, step / 100f)
            assertTrue(value >= previous)
            previous = value
        }
    }

    @Test
    fun `paper level and spread come from the paper, not the ink`() {
        val random = Random(7)
        // 85 % paper around 0.98 with noise 0.01, 15 % dark ink.
        val gray = FloatArray(20_000) { index ->
            if (index % 100 < 85) (0.98 + random.nextGaussian() * 0.01).toFloat() else 0.2f
        }
        val paper = EnhanceMath.paper(gray)
        assertEquals(0.98f, paper.level, 0.003f)
        assertEquals(0.01f, paper.spread, 0.002f)
        // Paper within three spreads of its level turns white: grain disappears.
        assertEquals(0.95f, paper.whitePoint, 0.008f)
    }

    @Test
    fun `a page that is mostly ink still gets sane numbers`() {
        val gray = FloatArray(1_000) { if (it < 950) 0.15f else 0.9f }
        val paper = EnhanceMath.paper(gray)
        assertTrue(paper.whitePoint in EnhanceMath.MIN_WHITE..EnhanceMath.MAX_WHITE)
        assertEquals(EnhanceMath.Paper(1f, 0f), EnhanceMath.paper(FloatArray(0)))
    }

    @Test
    fun `blank paper is never stretched, washed-out ink is brought back, normal ink is left alone`() {
        val paper = EnhanceMath.Paper(level = 0.98f, spread = 0.008f)
        // Noise-level "ink" (blank paper): no gain, so blank stays blank.
        assertEquals(1f, EnhanceMath.faintInkGain(0.03f, paper), 1e-6f)
        // Ink that glare washed out to 0.2 below the paper: brought back to the target depth.
        assertEquals(EnhanceMath.TARGET_INK_DEPTH / 0.2f, EnhanceMath.faintInkGain(0.2f, paper), 1e-5f)
        // Barely there (0.165): stretched as far as allowed.
        assertEquals(EnhanceMath.MAX_GAIN, EnhanceMath.faintInkGain(0.165f, paper), 1e-6f)
        // Fainter still (0.1), close to the noise: only partly, as it may be noise.
        assertEquals(1.704f, EnhanceMath.faintInkGain(0.1f, paper), 1e-3f)
        // Coloured text (about half as deep as black) and dark text: untouched, so colours stay true.
        assertEquals(1f, EnhanceMath.faintInkGain(0.5f, paper), 1e-6f)
        assertEquals(1f, EnhanceMath.faintInkGain(0.7f, paper), 1e-6f)
    }

    @Test
    fun `deepening faint ink never makes it darker than ink that was darker`() {
        val paper = EnhanceMath.Paper(level = 0.98f, spread = 0.008f)
        var previous = 0f
        for (step in 1..90) {
            val depth = step / 100f
            val result = depth * EnhanceMath.faintInkGain(depth, paper)
            assertTrue("depth $depth -> $result", result >= previous - 1e-5f)
            previous = result
        }
    }

    @Test
    fun `on a noisy page the faint-ink threshold rises with the noise`() {
        val quiet = EnhanceMath.Paper(level = 0.98f, spread = 0.005f)
        val noisy = EnhanceMath.Paper(level = 0.98f, spread = 0.03f)
        // A 0.12 dip is ink on a quiet page but within the noise on a noisy one.
        assertTrue(EnhanceMath.faintInkGain(0.12f, quiet) > 2f)
        assertEquals(1f, EnhanceMath.faintInkGain(0.12f, noisy), 1e-6f)
    }

    @Test
    fun `noise level is read from the median of the residual histogram`() {
        val histogram = FloatArray(256)
        histogram[3] = 60f
        histogram[40] = 40f
        // Median bin 3 at 4 bins per 8-bit level: (3.5 / 1020) scaled to a standard deviation.
        val expected = 1.4826f * 1.25f * 3.5f / 1020f
        assertEquals(expected, EnhanceMath.noiseSigma(histogram, binsPerUnit = 1020f), 1e-6f)
        assertEquals(0f, EnhanceMath.noiseSigma(FloatArray(256), binsPerUnit = 1020f))
    }

    @Test
    fun `specks are removed, punctuation and strokes are kept`() {
        val page = 1000
        // A single noisy pixel.
        assertTrue(EnhanceMath.isSpeck(area = 2, darkest = 0.3f, glare = 0f, longEdge = page))
        // A small light-grey fleck of paper texture.
        assertTrue(EnhanceMath.isSpeck(area = 25, darkest = 0.75f, glare = 0f, longEdge = page))
        // A full stop: small but dark.
        assertFalse(EnhanceMath.isSpeck(area = 20, darkest = 0.1f, glare = 0f, longEdge = page))
        // A long faint rule line: large.
        assertFalse(EnhanceMath.isSpeck(area = 900, darkest = 0.7f, glare = 0f, longEdge = page))
        // Light sparkle around a reflection goes; the same outside a reflection stays.
        assertTrue(EnhanceMath.isSpeck(area = 60, darkest = 0.6f, glare = 0.8f, longEdge = page))
        assertFalse(EnhanceMath.isSpeck(area = 60, darkest = 0.6f, glare = 0f, longEdge = page))
        // The dot of an i inside a reflection is darker: kept.
        assertFalse(EnhanceMath.isSpeck(area = 12, darkest = 0.5f, glare = 0.8f, longEdge = page))
    }

    @Test
    fun `speck sizes scale with the page`() {
        // The same fleck on a page twice as large covers four times the pixels.
        assertTrue(EnhanceMath.isSpeck(area = 100, darkest = 0.75f, glare = 0f, longEdge = 2000))
        assertFalse(EnhanceMath.isSpeck(area = 100, darkest = 0.75f, glare = 0f, longEdge = 1000))
    }

    @Test
    fun `saturation boost leaves greys grey and lifts colours`() {
        assertEquals(0.05f, EnhanceMath.saturation(0.05f, 1.22f), 1e-6f)
        assertEquals(0.6f * 1.22f, EnhanceMath.saturation(0.6f, 1.22f), 1e-5f)
        assertEquals(1f, EnhanceMath.saturation(0.95f, 1.22f))
        assertEquals(0.4f, EnhanceMath.saturation(0.4f, 1f), 1e-6f)
    }

    private fun Random.nextGaussian(): Double {
        // Box–Muller: enough for test data.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
