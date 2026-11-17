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
    fun `faint ink is not stretched inside solid areas`() {
        val paper = EnhanceMath.Paper(level = 0.98f, spread = 0.008f)
        val outside = EnhanceMath.faintInkGain(0.2f, paper)
        assertEquals(outside, EnhanceMath.faintInkGain(0.2f, paper, inSolidArea = 0f), 1e-6f)
        // A pale note or stamp: left as it is.
        assertEquals(1f, EnhanceMath.faintInkGain(0.2f, paper, inSolidArea = 1f), 1e-6f)
        // At its soft edge: half-way.
        assertEquals(1f + (outside - 1f) / 2f, EnhanceMath.faintInkGain(0.2f, paper, inSolidArea = 0.5f), 1e-5f)
    }

    @Test
    fun `shadows look like dimmed paper, ink and coloured areas do not`() {
        // Channels (red, green, blue) of the paper estimate divided by the paper nearby.
        assertTrue("warm brown shadow", EnhanceMath.isDimmedPaper(0.64f, 0.52f, 0.42f))
        assertTrue("deep warm shadow", EnhanceMath.isDimmedPaper(0.50f, 0.40f, 0.33f))
        assertTrue("grey shadow", EnhanceMath.isDimmedPaper(0.55f, 0.55f, 0.55f))
        assertTrue("cool shadow", EnhanceMath.isDimmedPaper(0.46f, 0.52f, 0.62f))
        assertFalse("green logo", EnhanceMath.isDimmedPaper(0.16f, 0.43f, 0.23f))
        assertFalse("red seal", EnhanceMath.isDimmedPaper(0.78f, 0.16f, 0.16f))
        assertFalse("black ink", EnhanceMath.isDimmedPaper(0.12f, 0.12f, 0.12f))
        assertFalse("yellow note: red and green barely lowered", EnhanceMath.isDimmedPaper(0.98f, 0.90f, 0.47f))
        assertFalse("paper itself", EnhanceMath.isDimmedPaper(0.97f, 0.96f, 0.95f))
        // Light changes colour smoothly with wavelength: green is never far below both red and blue, nor far above both.
        assertFalse("purple print", EnhanceMath.isDimmedPaper(0.55f, 0.40f, 0.62f))
        assertFalse("pale green print", EnhanceMath.isDimmedPaper(0.50f, 0.66f, 0.48f))
    }

    @Test
    fun `quantiles and spread are measured like the reference`() {
        val sorted = floatArrayOf(1f, 2f, 3f, 4f)
        // numpy.percentile([1, 2, 3, 4], 50) = 2.5, 75 -> 3.25
        assertEquals(2.5f, EnhanceMath.quantile(sorted, 0.5f), 1e-6f)
        assertEquals(3.25f, EnhanceMath.quantile(sorted, 0.75f), 1e-6f)
        assertEquals(0f, EnhanceMath.quantile(FloatArray(0), 0.5f))
        // One outlier does not widen the robust spread.
        assertEquals(0f, EnhanceMath.spread(floatArrayOf(1f, 1f, 1f, 10f)), 1e-6f)
        assertEquals(1.4826f, EnhanceMath.spread(floatArrayOf(1f, 2f, 3f)), 1e-5f)
    }

    @Test
    fun `regions are labelled with their area and whether they reach the border`() {
        // 6 × 4:  A A . . . .
        //         . . . . . .
        //         . . B . . .
        //         . . . C . .
        val mask = BooleanArray(24)
        for (index in intArrayOf(0, 1, 14, 21)) mask[index] = true
        val sides = EnhanceMath.regions(mask, cols = 6, rows = 4, diagonal = false)
        assertEquals(4, sides.areas.size) // background + A, B, C
        assertEquals(2, sides.areas[sides.labels[0]])
        assertTrue(sides.touchesBorder[sides.labels[0]])
        assertFalse(sides.touchesBorder[sides.labels[14]])
        assertTrue(sides.touchesBorder[sides.labels[21]])
        assertEquals(0, sides.labels[2])
        // B and C touch at a corner: one region when corners count.
        val corners = EnhanceMath.regions(mask, cols = 6, rows = 4, diagonal = true)
        assertEquals(3, corners.areas.size)
        assertEquals(corners.labels[14], corners.labels[21])
        assertEquals(2, corners.areas[corners.labels[14]])
    }

    @Test
    fun `a smooth, evenly coloured region reaching the edge is a shadow`() {
        val page = Page(cols = 100, rows = 60)
        // A hand's shadow from the top edge: smooth and one colour inside.
        page.region(10..35, 0..40)
        assertTrue(page.shadows()[page.at(20, 20)])
        assertFalse(page.shadows()[page.at(60, 50)])
    }

    @Test
    fun `pictures, printed colour and islands are not shadows`() {
        val page = Page(cols = 100, rows = 60)
        // A photo running off the right edge: many edges inside.
        page.region(60..99, 0..40, gradient = { x, y -> if ((x + y) % 3 == 0) 0.02f else 0.0005f })
        // A printed area at the bottom edge: smooth, but its colour changes across it (a picture's sky, a gradient).
        page.region(5..40, 45..59, hueRedGreen = { x, _ -> 0.01f * x })
        // An island: smooth and even, but clear of the page's edge (a grey box).
        page.region(20..45, 8..35)
        // At the edge but too thin to have an inside.
        page.region(0..3, 10..40)
        val shadows = page.shadows()
        assertFalse(shadows[page.at(80, 20)])
        assertFalse(shadows[page.at(20, 50)])
        assertFalse(shadows[page.at(30, 20)])
        assertFalse(shadows[page.at(1, 20)])
    }

    @Test
    fun `a photo merged with a shadow keeps the whole region out`() {
        val page = Page(cols = 100, rows = 60)
        page.region(10..35, 0..40)
        // The same region continues into a picture: half of its inside is textured.
        page.region(36..70, 0..40, gradient = { x, _ -> if (x % 2 == 0) 0.01f else 0.004f })
        assertFalse(page.shadows()[page.at(20, 20)])
    }

    /** Candidate regions and their per-pixel statistics at the working size, for [EnhanceMath.shadows]. */
    private class Page(val cols: Int, val rows: Int) {
        val candidates = BooleanArray(cols * rows)
        val gradient = FloatArray(cols * rows)
        val hueRedGreen = FloatArray(cols * rows)
        val hueBlueGreen = FloatArray(cols * rows)

        fun at(x: Int, y: Int) = y * cols + x

        fun region(
            xs: IntRange,
            ys: IntRange,
            gradient: (Int, Int) -> Float = { _, _ -> 0.0003f },
            hueRedGreen: (Int, Int) -> Float = { _, _ -> 0.20f },
        ) {
            for (y in ys) for (x in xs) {
                candidates[at(x, y)] = true
                this.gradient[at(x, y)] = gradient(x, y)
                this.hueRedGreen[at(x, y)] = hueRedGreen(x, y)
                hueBlueGreen[at(x, y)] = -0.15f
            }
        }

        /** Inside: candidates whose 5 × 5 neighbourhood is all candidates (pixels past the image edge count as inside). */
        fun shadows(): BooleanArray {
            val inside = BooleanArray(cols * rows) { index ->
                val x = index % cols
                val y = index / cols
                (-2..2).all { dy -> (-2..2).all { dx -> (x + dx) !in 0 until cols || (y + dy) !in 0 until rows || candidates[at(x + dx, y + dy)] } }
            }
            return EnhanceMath.shadows(candidates, inside, gradient, hueRedGreen, hueBlueGreen, cols, rows)
        }
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
