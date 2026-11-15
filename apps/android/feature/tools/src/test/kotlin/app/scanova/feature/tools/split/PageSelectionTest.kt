package app.scanova.feature.tools.split

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSelectionTest {

    @Test
    fun `ranges and single pages become parts, in the order typed`() {
        assertEquals(
            RangeParse.Valid(listOf(listOf(0, 1, 2), listOf(4), listOf(6, 7))),
            PageSelection.parseRanges("1-3, 5; 7–8", pageCount = 10),
        )
    }

    @Test
    fun `blanks and empty items are ignored`() {
        assertEquals(RangeParse.Valid(listOf(listOf(1, 2))), PageSelection.parseRanges("  2 - 3 ,, ", pageCount = 3))
    }

    @Test
    fun `invalid ranges say what is wrong`() {
        assertEquals(RangeParse.Invalid(RangeError.EMPTY), PageSelection.parseRanges(" , ", 5))
        assertEquals(RangeParse.Invalid(RangeError.SYNTAX), PageSelection.parseRanges("1-", 5))
        assertEquals(RangeParse.Invalid(RangeError.SYNTAX), PageSelection.parseRanges("a-b", 5))
        assertEquals(RangeParse.Invalid(RangeError.SYNTAX), PageSelection.parseRanges("1-2-3", 5))
        assertEquals(RangeParse.Invalid(RangeError.OUT_OF_BOUNDS), PageSelection.parseRanges("0-2", 5))
        assertEquals(RangeParse.Invalid(RangeError.OUT_OF_BOUNDS), PageSelection.parseRanges("4-6", 5))
        assertEquals(RangeParse.Invalid(RangeError.REVERSED), PageSelection.parseRanges("4-2", 5))
    }

    @Test
    fun `pages are described compactly and 1-based`() {
        assertEquals("1–3, 5, 7–8", PageSelection.describe(listOf(6, 0, 2, 1, 4, 7, 2)))
        assertEquals("4", PageSelection.describe(setOf(3)))
        assertEquals("", PageSelection.describe(emptyList()))
    }

    @Test
    fun `the selected pages make one document in page order`() {
        assertEquals(listOf(listOf(0, 2, 5)), PageSelection.plan(SplitMode.SELECTED, setOf(5, 0, 2, 9), "", pageCount = 6))
        assertEquals(emptyList<List<Int>>(), PageSelection.plan(SplitMode.SELECTED, emptySet(), "", pageCount = 6))
    }

    @Test
    fun `every page makes its own document`() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), PageSelection.plan(SplitMode.EACH_PAGE, emptySet(), "", pageCount = 3))
    }

    @Test
    fun `typed ranges make one document each, nothing while invalid`() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), PageSelection.plan(SplitMode.RANGES, emptySet(), "1-2, 3", pageCount = 3))
        assertEquals(emptyList<List<Int>>(), PageSelection.plan(SplitMode.RANGES, emptySet(), "1-9", pageCount = 3))
    }
}
