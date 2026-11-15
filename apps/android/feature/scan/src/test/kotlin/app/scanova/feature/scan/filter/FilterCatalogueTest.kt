package app.scanova.feature.scan.filter

import app.scanova.core.data.model.PageFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCatalogueTest {

    @Test
    fun `offers at least twelve filters`() {
        assertTrue(FilterCatalogue.all.size >= 12)
    }

    @Test
    fun `ids are unique`() {
        val ids = FilterCatalogue.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every filter has its own name and its own algorithm`() {
        assertEquals(FilterCatalogue.all.size, FilterCatalogue.all.map { it.label }.toSet().size)
        assertEquals(FilterCatalogue.all.size, FilterCatalogue.all.map { it.algorithm }.toSet().size)
    }

    @Test
    fun `ids are short lowercase words safe to store anywhere`() {
        FilterCatalogue.all.forEach { assertTrue(it.id, it.id.matches(Regex("[a-z][a-z_]{1,31}"))) }
    }

    @Test
    fun `the first scanner's filters keep their ids and their looks`() {
        assertEquals(FilterAlgorithm.NONE, FilterCatalogue.byId(PageFilters.ORIGINAL).algorithm)
        assertEquals(FilterAlgorithm.AUTO, FilterCatalogue.byId(PageFilters.ENHANCED).algorithm)
        assertEquals(FilterAlgorithm.GREYSCALE, FilterCatalogue.byId(PageFilters.GREYSCALE).algorithm)
        assertEquals(FilterAlgorithm.BLACK_WHITE, FilterCatalogue.byId(PageFilters.BLACK_WHITE).algorithm)
        assertEquals("original", PageFilters.ORIGINAL)
        assertEquals("enhanced", PageFilters.ENHANCED)
        assertEquals("greyscale", PageFilters.GREYSCALE)
        assertEquals("black_white", PageFilters.BLACK_WHITE)
    }

    /**
     * Ids are stored in recipes on phones and on the server: this list may
     * grow, but an entry may never change or disappear.
     */
    @Test
    fun `released ids never change`() {
        val released = listOf(
            "original", "enhanced", "magic_color", "no_shadow", "lighten", "sharpen", "greyscale",
            "black_white", "bw_sharp", "ink_saver", "vivid", "warm", "cool", "vintage",
        )
        val ids = FilterCatalogue.all.map { it.id }
        released.forEach { assertTrue("missing released id $it", it in ids) }
    }

    @Test
    fun `original comes first`() {
        assertEquals(PageFilters.ORIGINAL, FilterCatalogue.all.first().id)
        assertEquals(FilterCatalogue.original, FilterCatalogue.all.first())
    }

    @Test
    fun `unknown or missing ids render as the original`() {
        assertEquals(FilterCatalogue.original, FilterCatalogue.byId("from_a_newer_version"))
        assertEquals(FilterCatalogue.original, FilterCatalogue.byId(null))
        assertFalse(FilterCatalogue.isKnown("from_a_newer_version"))
        assertTrue(FilterCatalogue.isKnown(PageFilters.ENHANCED))
    }

    @Test
    fun `every filter has a stand-in when OpenCV is missing`() {
        FilterCatalogue.all.filter { it.algorithm != FilterAlgorithm.NONE }.forEach { filter ->
            val matrix = ColorMatrices.fallback(filter.algorithm)
            assertTrue(filter.id, matrix != null && matrix.size == 20)
        }
    }
}
