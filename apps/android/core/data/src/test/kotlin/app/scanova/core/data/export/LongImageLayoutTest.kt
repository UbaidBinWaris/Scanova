package app.scanova.core.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongImageLayoutTest {

    private val a4 = PixelSize(1654, 2339)

    @Test
    fun `no pages make no image`() {
        assertTrue(LongImageLayout.plan(emptyList()).isEmpty())
    }

    @Test
    fun `pages are scaled to a common width no wider than the cap`() {
        val parts = LongImageLayout.plan(listOf(PixelSize(2400, 3200), PixelSize(1200, 1600)), maxWidth = 1600)

        assertEquals(1, parts.size)
        val part = parts.single()
        val gap = LongImageLayout.gapFor(1600)
        assertEquals(1600, part.width)
        part.placements.forEach { placement ->
            assertEquals(1600 - 2 * gap, placement.width)
            assertEquals(gap, placement.left)
            // 3:4 pages keep their proportions.
            assertEquals(placement.width * 4 / 3, placement.height)
        }
    }

    @Test
    fun `narrow documents are not enlarged beyond their widest page`() {
        val part = LongImageLayout.plan(listOf(PixelSize(800, 1000), PixelSize(400, 500)), maxWidth = 1600).single()
        val gap = LongImageLayout.gapFor(800)

        assertEquals(800 + 2 * gap, part.width)
        assertEquals(listOf(800, 800), part.placements.map { it.width })
        assertEquals(listOf(1000, 1000), part.placements.map { it.height })
    }

    @Test
    fun `pages are stacked with equal gaps and a margin all round`() {
        val part = LongImageLayout.plan(listOf(a4, a4, a4), maxWidth = 1000).single()
        val gap = LongImageLayout.gapFor(1000)
        val placements = part.placements

        assertEquals(gap, placements.first().top)
        placements.zipWithNext { above, below -> assertEquals(above.top + above.height + gap, below.top) }
        assertEquals(placements.last().top + placements.last().height + gap, part.height)
        assertEquals(listOf(0, 1, 2), placements.map { it.pageIndex })
    }

    @Test
    fun `long documents are split into parts under the height cap without cutting pages`() {
        val pages = List(30) { a4 }
        val parts = LongImageLayout.plan(pages, maxWidth = 1600, maxHeight = 16_000)

        assertTrue("expected several parts", parts.size > 1)
        parts.forEach { part ->
            assertTrue("part too tall: ${part.height}", part.height <= 16_000)
            part.placements.forEach { assertTrue(it.top + it.height <= part.height) }
        }
        // Every page appears exactly once, in order.
        assertEquals((0 until 30).toList(), parts.flatMap { it.placements }.map { it.pageIndex })
        // Parts are filled greedily: the next page would not have fitted in the previous part.
        parts.zipWithNext { previous, next ->
            val nextPage = next.placements.first()
            assertTrue(previous.height + nextPage.height + LongImageLayout.gapFor(1600) > 16_000)
        }
    }

    @Test
    fun `a single page taller than the cap is scaled down to fit and centred`() {
        val receipt = PixelSize(800, 40_000)
        val parts = LongImageLayout.plan(listOf(a4, receipt, a4), maxWidth = 1600, maxHeight = 16_000)

        val tall = parts.flatMap { it.placements }.single { it.pageIndex == 1 }
        val part = parts.single { part -> part.placements.any { it.pageIndex == 1 } }
        val gap = LongImageLayout.gapFor(1600)
        assertEquals(16_000 - 2 * gap, tall.height)
        assertEquals((800L * tall.height / 40_000).toInt(), tall.width)
        assertEquals((part.width - tall.width) / 2, tall.left, 1)
        assertTrue(parts.all { it.height <= 16_000 })
        assertEquals(3, parts.size)
    }

    @Test
    fun `pages without a usable size are laid out as A4`() {
        val part = LongImageLayout.plan(listOf(PixelSize(0, 0)), maxWidth = 1240).single()
        val placement = part.placements.single()
        assertTrue(placement.height > placement.width)
    }

    @Test
    fun `gap is two percent of the width but never below eight pixels`() {
        assertEquals(32, LongImageLayout.gapFor(1600))
        assertEquals(8, LongImageLayout.gapFor(100))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected ± $tolerance but was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }
}
