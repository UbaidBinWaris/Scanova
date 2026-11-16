package app.scanova.feature.scan.filter

import app.scanova.core.data.model.PageFilters

/**
 * The Server clean-up look ([PageFilters.SERVER_CLEANUP]): the flattened page
 * is sent to the owner's server, where GCDRNet removes shadows, colour casts
 * and bleed-through (docs/ai-image-research.md).
 *
 * The cleaned image is a per-page layer, made once for each geometry of the
 * page ([CleanedPages][app.scanova.feature.scan.cleanup.CleanedPages]) and
 * then used like any filter's result: strength, brightness and contrast apply
 * to it as usual. A page without one (not cleaned yet, or clean-up not
 * available) is drawn with [STAND_IN], so a page always renders.
 *
 * Pure rules, unit-tested.
 */
internal object ServerCleanup {
    const val ID = PageFilters.SERVER_CLEANUP

    /** Drawn in place of the cleaned image while a page has none: the closest look made on the phone. */
    const val STAND_IN = PageFilters.ENHANCED

    fun isCleanup(filterId: String?): Boolean = filterId == ID

    /**
     * Server clean-up is offered in the filter row while this account can
     * clean pages, and on a page that already uses it (its own look stays
     * visible and selectable after the switch is turned off).
     */
    fun isOffered(available: Boolean, currentFilterId: String?): Boolean = available || isCleanup(currentFilterId)

    /** The catalogue as the filter row offers it for a page whose filter is [currentFilterId]. */
    fun offeredFilters(available: Boolean, currentFilterId: String?): List<PageFilter> =
        if (isOffered(available, currentFilterId)) FilterCatalogue.all else FilterCatalogue.all.filterNot { isCleanup(it.id) }

    /**
     * Where the filtered layer of a page with filter [filterId] comes from,
     * given whether a cleaned image exists for the page's current geometry.
     */
    fun source(filterId: String, hasCleaned: Boolean): LookSource = when {
        !isCleanup(filterId) -> LookSource.Local(filterId)
        hasCleaned -> LookSource.Cleaned
        else -> LookSource.Local(STAND_IN, standIn = true)
    }

    /** The page must be cleaned before it is rendered: its look is Server clean-up and nothing cleaned is kept for its geometry. */
    fun needsCleaning(filterId: String, hasCleaned: Boolean): Boolean = isCleanup(filterId) && !hasCleaned

    /**
     * A page is rendered again when its image does not match its recipe, or,
     * when asked to [retry] (Server clean-up chosen again), when it shows its
     * stand-in because its clean-up failed. A failed clean-up is never retried
     * on its own: only a change or a new choice tries again.
     */
    fun rendersAgain(upToDate: Boolean, cleanupMissing: Boolean, retry: Boolean): Boolean = !upToDate || (retry && cleanupMissing)
}

/** Where a page's filtered layer comes from. */
internal sealed interface LookSource {
    /** The page's cleaned image, made by the server (Server clean-up). */
    data object Cleaned : LookSource

    /**
     * Filter [filterId] applied on the phone ([FilterEngine]). [standIn]: it is
     * drawn in place of a cleaned image the page does not have (yet).
     */
    data class Local(val filterId: String, val standIn: Boolean = false) : LookSource
}
