package app.scanova.feature.scan.filter

import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageFilters
import app.scanova.feature.scan.render.look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Server clean-up look: where its layer comes from, and when it is offered. */
class ServerCleanupTest {

    // ── Look selection (renderer and live preview) ─────────────────────

    @Test
    fun `a page with its cleaned image draws the cleaned image`() {
        assertEquals(LookSource.Cleaned, ServerCleanup.source(PageFilters.SERVER_CLEANUP, hasCleaned = true))
    }

    @Test
    fun `without a cleaned image the page falls back to the Auto look, marked as a stand-in`() {
        val source = ServerCleanup.source(PageFilters.SERVER_CLEANUP, hasCleaned = false)
        assertEquals(LookSource.Local(PageFilters.ENHANCED, standIn = true), source)
    }

    @Test
    fun `other filters are made on the phone whatever is kept`() {
        listOf(PageFilters.ORIGINAL, PageFilters.ENHANCED, PageFilters.BLACK_WHITE, "from_a_newer_version").forEach { id ->
            assertEquals(LookSource.Local(id), ServerCleanup.source(id, hasCleaned = true))
            assertEquals(LookSource.Local(id), ServerCleanup.source(id, hasCleaned = false))
        }
    }

    @Test
    fun `the stand-in is a filter the phone can draw, and the engine draws Server clean-up as it`() {
        assertTrue(FilterCatalogue.isKnown(ServerCleanup.STAND_IN))
        assertFalse(ServerCleanup.isCleanup(ServerCleanup.STAND_IN))
        assertEquals(FilterAlgorithm.AUTO, FilterCatalogue.byId(ServerCleanup.STAND_IN).algorithm)
        // Without OpenCV the stand-in look is the Auto look's stand-in too.
        assertTrue(ColorMatrices.fallback(FilterAlgorithm.SERVER_CLEANUP).contentEquals(ColorMatrices.fallback(FilterAlgorithm.AUTO)))
    }

    @Test
    fun `only Server clean-up ever needs the server`() {
        assertTrue(ServerCleanup.needsCleaning(PageFilters.SERVER_CLEANUP, hasCleaned = false))
        assertFalse(ServerCleanup.needsCleaning(PageFilters.SERVER_CLEANUP, hasCleaned = true))
        assertFalse(ServerCleanup.needsCleaning(PageFilters.ENHANCED, hasCleaned = false))
    }

    // ── Catalogue ───────────────────────────────────────────────────────

    @Test
    fun `Server clean-up is a catalogue filter with its own id, name and algorithm`() {
        assertEquals("server_cleanup", PageFilters.SERVER_CLEANUP)
        assertTrue(FilterCatalogue.isKnown(PageFilters.SERVER_CLEANUP))
        assertEquals(FilterAlgorithm.SERVER_CLEANUP, FilterCatalogue.byId(PageFilters.SERVER_CLEANUP).algorithm)
        // A recipe keeps it as its look, like any filter id.
        assertEquals(PageFilters.SERVER_CLEANUP, PageEdit(filter = PageFilters.SERVER_CLEANUP).look.filterId)
    }

    // ── Filter row ──────────────────────────────────────────────────────

    @Test
    fun `offered while clean-up is available, or on a page that already uses it`() {
        assertTrue(ServerCleanup.isOffered(available = true, currentFilterId = PageFilters.ENHANCED))
        assertTrue(ServerCleanup.isOffered(available = false, currentFilterId = PageFilters.SERVER_CLEANUP))
        assertTrue(ServerCleanup.isOffered(available = true, currentFilterId = PageFilters.SERVER_CLEANUP))
        assertFalse(ServerCleanup.isOffered(available = false, currentFilterId = PageFilters.ENHANCED))
        assertFalse(ServerCleanup.isOffered(available = false, currentFilterId = null))
    }

    @Test
    fun `the filter row leaves Server clean-up out otherwise and keeps every other filter in order`() {
        val offered = ServerCleanup.offeredFilters(available = false, currentFilterId = PageFilters.ORIGINAL)
        assertFalse(offered.any { it.id == PageFilters.SERVER_CLEANUP })
        assertEquals(FilterCatalogue.all.filterNot { it.id == PageFilters.SERVER_CLEANUP }, offered)

        assertEquals(FilterCatalogue.all, ServerCleanup.offeredFilters(available = true, currentFilterId = PageFilters.ORIGINAL))
        assertEquals(FilterCatalogue.all, ServerCleanup.offeredFilters(available = false, currentFilterId = PageFilters.SERVER_CLEANUP))
    }

    // ── Retries ─────────────────────────────────────────────────────────

    @Test
    fun `a failed clean-up is tried again only on a change or when chosen again`() {
        // Changed: rendered whatever happened before.
        assertTrue(ServerCleanup.rendersAgain(upToDate = false, cleanupMissing = false, retry = false))
        assertTrue(ServerCleanup.rendersAgain(upToDate = false, cleanupMissing = true, retry = false))
        // Up to date with its stand-in: left alone, so a failure never loops...
        assertFalse(ServerCleanup.rendersAgain(upToDate = true, cleanupMissing = true, retry = false))
        // ...until Server clean-up is chosen again.
        assertTrue(ServerCleanup.rendersAgain(upToDate = true, cleanupMissing = true, retry = true))
        // A page that was cleaned is not sent again for choosing it again.
        assertFalse(ServerCleanup.rendersAgain(upToDate = true, cleanupMissing = false, retry = true))
    }
}
