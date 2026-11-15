package app.scanova.core.data.export

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where one page is drawn inside a long image, in pixels of that image. */
data class PagePlacement(
    /** Index of the page in the document. */
    val pageIndex: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** One long image: a white canvas of [width] × [height] with pages drawn at [placements]. */
data class LongImagePart(
    val width: Int,
    val height: Int,
    val placements: List<PagePlacement>,
)

/**
 * Lays the pages of a document out under each other for a "long image": all
 * pages scaled to one common width (never wider than [MAX_WIDTH]), with small
 * white gaps between them and around the edge.
 *
 * A very long document would make an image too tall for other apps (and for
 * memory), so the pages are split into several parts, each at most
 * [MAX_HEIGHT] pixels tall. Pages are never cut: a part ends before the page
 * that would not fit. A single page taller than the limit on its own (a long
 * receipt) is scaled down to fit and centred.
 *
 * Pure arithmetic, so it is unit tested without Android.
 */
object LongImageLayout {
    const val MAX_WIDTH = 1600
    const val MAX_HEIGHT = 16_000

    /** A page with no usable size is laid out as A4 portrait. */
    private val FALLBACK = PixelSize(1240, 1754)

    /** The gap between pages (and around them) for an image [width] pixels wide: 2 %, at least 8 px. */
    fun gapFor(width: Int): Int = max(MIN_GAP, (width * GAP_FRACTION).roundToInt())

    /**
     * Plans the parts for [pages] (sizes in pixels, in document order).
     * [maxWidth] caps the image width; pages narrower than the widest are
     * scaled up to the common width, which itself is never more than the widest
     * page (nothing is enlarged beyond the best page available).
     */
    fun plan(
        pages: List<PixelSize>,
        maxWidth: Int = MAX_WIDTH,
        maxHeight: Int = MAX_HEIGHT,
    ): List<LongImagePart> {
        if (pages.isEmpty()) return emptyList()
        require(maxWidth > 0 && maxHeight > 0) { "bad_limits" }
        val sizes = pages.map { if (it.width > 0 && it.height > 0) it else FALLBACK }

        val widest = sizes.maxOf { it.width }
        val gap = gapFor(min(maxWidth, widest))
        val contentWidth = max(1, min(maxWidth - 2 * gap, widest))
        val canvasWidth = contentWidth + 2 * gap
        // The tallest page a part can hold with its margins.
        val maxPageHeight = max(1, maxHeight - 2 * gap)

        val parts = mutableListOf<LongImagePart>()
        var placements = mutableListOf<PagePlacement>()
        var y = gap
        sizes.forEachIndexed { index, size ->
            var width = contentWidth
            var height = max(1, (size.height.toLong() * contentWidth / size.width).toInt())
            if (height > maxPageHeight) {
                height = maxPageHeight
                width = max(1, (size.width.toLong() * maxPageHeight / size.height).toInt())
            }
            if (placements.isNotEmpty() && y + height + gap > maxHeight) {
                parts += LongImagePart(canvasWidth, y, placements)
                placements = mutableListOf()
                y = gap
            }
            placements += PagePlacement(
                pageIndex = index,
                left = gap + (contentWidth - width) / 2,
                top = y,
                width = width,
                height = height,
            )
            y += height + gap
        }
        parts += LongImagePart(canvasWidth, y, placements)
        return parts
    }

    private const val MIN_GAP = 8
    private const val GAP_FRACTION = 0.02
}
