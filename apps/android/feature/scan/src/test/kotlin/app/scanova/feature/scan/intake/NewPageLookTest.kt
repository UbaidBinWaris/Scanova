package app.scanova.feature.scan.intake

import app.scanova.core.data.model.PageFilters
import app.scanova.feature.scan.render.PageLook
import org.junit.Assert.assertEquals
import org.junit.Test

class NewPageLookTest {

    @Test
    fun `auto-enhance off keeps the original look`() {
        val previous = PageLook(PageFilters.ENHANCED, strength = 0.5f)
        assertEquals(PageLook(PageFilters.ORIGINAL), NewPageLook.choose(autoEnhance = false, defaultFilter = PageFilters.ENHANCED, previous = previous))
    }

    @Test
    fun `a new page follows the look of the page before it`() {
        val previous = PageLook(PageFilters.ENHANCED, strength = 0.6f, brightness = 0.1f)
        assertEquals(previous, NewPageLook.choose(autoEnhance = true, defaultFilter = PageFilters.ENHANCED, previous = previous))
    }

    @Test
    fun `the first page, or one after an unfiltered page, gets the default filter`() {
        assertEquals(PageLook(PageFilters.ENHANCED), NewPageLook.choose(autoEnhance = true, defaultFilter = PageFilters.ENHANCED, previous = null))
        assertEquals(
            PageLook(PageFilters.ENHANCED),
            NewPageLook.choose(autoEnhance = true, defaultFilter = PageFilters.ENHANCED, previous = PageLook(PageFilters.ORIGINAL)),
        )
    }

    @Test
    fun `an unknown or original default filter falls back to the enhanced look`() {
        assertEquals(PageLook(PageFilters.ENHANCED), NewPageLook.choose(autoEnhance = true, defaultFilter = "no-such-filter", previous = null))
        assertEquals(PageLook(PageFilters.ENHANCED), NewPageLook.choose(autoEnhance = true, defaultFilter = PageFilters.ORIGINAL, previous = null))
        assertEquals(PageLook(PageFilters.ENHANCED), NewPageLook.choose(autoEnhance = true, defaultFilter = null, previous = null))
    }
}
