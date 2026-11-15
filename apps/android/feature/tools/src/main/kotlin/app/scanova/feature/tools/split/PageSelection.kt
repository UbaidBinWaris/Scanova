package app.scanova.feature.tools.split

/** How a document is split. Every mode leaves the original document as it is. */
internal enum class SplitMode {
    /** The chosen pages become one new document. */
    SELECTED,

    /** Every page becomes a document of its own. */
    EACH_PAGE,

    /** Each typed range ("1-3, 4-6") becomes a document. */
    RANGES,
}

/** Why typed page ranges cannot be used. */
internal enum class RangeError { EMPTY, SYNTAX, OUT_OF_BOUNDS, REVERSED }

internal sealed interface RangeParse {
    /** One list of 0-based page indexes per range, in the order typed. */
    data class Valid(val parts: List<List<Int>>) : RangeParse

    data class Invalid(val error: RangeError) : RangeParse
}

/**
 * The page arithmetic of the split tool: parsing "1-3, 5" into parts,
 * describing a set of pages compactly, and planning the documents to create.
 * Users count pages from 1; everything here returns 0-based indexes.
 */
internal object PageSelection {

    private val RANGE = Regex("""^(\d{1,5})\s*[-–—]\s*(\d{1,5})$""")
    private val SINGLE = Regex("""^\d{1,5}$""")

    /**
     * Parses ranges such as `1-3, 5, 7–9` (commas or semicolons between them,
     * any dash between the ends). Each range becomes one part; ranges may
     * overlap (a page can go into several parts).
     */
    fun parseRanges(text: String, pageCount: Int): RangeParse {
        val items = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return RangeParse.Invalid(RangeError.EMPTY)
        val parts = mutableListOf<List<Int>>()
        for (item in items) {
            val (first, last) = when {
                SINGLE.matches(item) -> item.toInt().let { it to it }
                else -> RANGE.matchEntire(item)?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
                    ?: return RangeParse.Invalid(RangeError.SYNTAX)
            }
            if (first < 1 || last > pageCount) return RangeParse.Invalid(RangeError.OUT_OF_BOUNDS)
            if (last < first) return RangeParse.Invalid(RangeError.REVERSED)
            parts += (first - 1 until last).toList()
        }
        return RangeParse.Valid(parts)
    }

    /** 0-based [pages] as people read them: `1–3, 5, 7–8` (sorted, without repeats). */
    fun describe(pages: Collection<Int>): String {
        val sorted = pages.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val runs = mutableListOf<IntRange>()
        var start = sorted.first()
        var end = start
        for (page in sorted.drop(1)) {
            if (page == end + 1) {
                end = page
            } else {
                runs += start..end
                start = page
                end = page
            }
        }
        runs += start..end
        return runs.joinToString(", ") { run ->
            if (run.first == run.last) "${run.first + 1}" else "${run.first + 1}–${run.last + 1}"
        }
    }

    /**
     * The documents a split creates, each as 0-based page indexes in document
     * order (ranges keep the order they were typed in). Empty when there is
     * nothing to create yet.
     */
    fun plan(mode: SplitMode, selected: Set<Int>, rangesText: String, pageCount: Int): List<List<Int>> = when (mode) {
        SplitMode.SELECTED -> selected.filter { it in 0 until pageCount }.sorted()
            .takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
        SplitMode.EACH_PAGE -> (0 until pageCount).map { listOf(it) }
        SplitMode.RANGES -> (parseRanges(rangesText, pageCount) as? RangeParse.Valid)?.parts.orEmpty()
    }
}
