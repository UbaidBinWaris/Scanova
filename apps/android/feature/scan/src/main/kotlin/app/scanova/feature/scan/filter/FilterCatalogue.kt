package app.scanova.feature.scan.filter

import androidx.annotation.StringRes
import app.scanova.core.data.model.PageFilters
import app.scanova.feature.scan.R

/** How a filter changes a page; each one is implemented by [FilterEngine]. */
internal enum class FilterAlgorithm {
    /** Leaves the page as it is. */
    NONE,

    /** Local contrast on lightness only: crisper text, true colours (the legacy "enhanced"). */
    AUTO,

    /** Paper made white and even, colours made bright: the usual best look for colour documents. */
    MAGIC_COLOR,

    /** Shadows and uneven lighting removed, colours kept. */
    NO_SHADOW,

    /** Mid-tones lifted: for dim photos. */
    LIGHTEN,

    /** Sharper strokes for small or soft print. */
    SHARPEN,

    GREYSCALE,

    /** Black ink on white, decided per neighbourhood (the legacy "black & white"). */
    BLACK_WHITE,

    /** Black ink on white after evening out the lighting: crisp and clean. */
    BW_SHARP,

    /** Even, light grey ink on white: uses less toner when printed. */
    INK_SAVER,

    /** Colour filters built from one colour matrix ([ColorMatrices]). */
    VIVID,
    WARM,
    COOL,
    VINTAGE,
}

/**
 * One entry of the filter catalogue. [id] is stored in page recipes (on the
 * phone and on the server), so it never changes once released.
 */
internal data class PageFilter(
    val id: String,
    @param:StringRes val label: Int,
    val algorithm: FilterAlgorithm,
)

/**
 * Every filter a page can have, in the order they are offered.
 *
 * Ids are permanent: a filter that is replaced keeps its id with a new look
 * only when the new look is close to the old one; otherwise it gets a new id.
 * The first four are the ids of the original scanner ([PageFilters]).
 */
internal object FilterCatalogue {
    const val MAGIC_COLOR = "magic_color"
    const val NO_SHADOW = "no_shadow"
    const val LIGHTEN = "lighten"
    const val SHARPEN = "sharpen"
    const val BW_SHARP = "bw_sharp"
    const val INK_SAVER = "ink_saver"
    const val VIVID = "vivid"
    const val WARM = "warm"
    const val COOL = "cool"
    const val VINTAGE = "vintage"

    val original = PageFilter(PageFilters.ORIGINAL, R.string.feature_scan_filter_original, FilterAlgorithm.NONE)

    val all: List<PageFilter> = listOf(
        original,
        PageFilter(PageFilters.ENHANCED, R.string.feature_scan_filter_auto, FilterAlgorithm.AUTO),
        PageFilter(MAGIC_COLOR, R.string.feature_scan_filter_magic_color, FilterAlgorithm.MAGIC_COLOR),
        PageFilter(NO_SHADOW, R.string.feature_scan_filter_no_shadow, FilterAlgorithm.NO_SHADOW),
        PageFilter(LIGHTEN, R.string.feature_scan_filter_lighten, FilterAlgorithm.LIGHTEN),
        PageFilter(SHARPEN, R.string.feature_scan_filter_sharpen, FilterAlgorithm.SHARPEN),
        PageFilter(PageFilters.GREYSCALE, R.string.feature_scan_filter_greyscale, FilterAlgorithm.GREYSCALE),
        PageFilter(PageFilters.BLACK_WHITE, R.string.feature_scan_filter_black_white, FilterAlgorithm.BLACK_WHITE),
        PageFilter(BW_SHARP, R.string.feature_scan_filter_bw_sharp, FilterAlgorithm.BW_SHARP),
        PageFilter(INK_SAVER, R.string.feature_scan_filter_ink_saver, FilterAlgorithm.INK_SAVER),
        PageFilter(VIVID, R.string.feature_scan_filter_vivid, FilterAlgorithm.VIVID),
        PageFilter(WARM, R.string.feature_scan_filter_warm, FilterAlgorithm.WARM),
        PageFilter(COOL, R.string.feature_scan_filter_cool, FilterAlgorithm.COOL),
        PageFilter(VINTAGE, R.string.feature_scan_filter_vintage, FilterAlgorithm.VINTAGE),
    )

    private val byId: Map<String, PageFilter> = all.associateBy { it.id }

    /** The filter recorded as [id]. Unknown ids (from a newer app version) render as [original]. */
    fun byId(id: String?): PageFilter = id?.let(byId::get) ?: original

    fun isKnown(id: String?): Boolean = id != null && id in byId
}
