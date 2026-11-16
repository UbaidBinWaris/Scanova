package app.scanova.feature.scan.cleanup

import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageFilters
import app.scanova.core.data.model.PageMesh
import app.scanova.core.data.model.PagePoint
import app.scanova.feature.scan.render.geometryKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory store of pages cleaned by the server. */
class CleanedPagesTest {

    private val crop = listOf(PagePoint(0.1f, 0.1f), PagePoint(0.9f, 0.1f), PagePoint(0.9f, 0.9f), PagePoint(0.1f, 0.9f))
    private val edit = PageEdit(crop = crop, filter = PageFilters.SERVER_CLEANUP)
    private val jpeg = byteArrayOf(1, 2, 3)

    @Test
    fun `a cleaned page is kept for its page and its geometry only`() {
        val pages = CleanedPages()
        pages.put("a", edit.geometryKey, jpeg)

        assertArrayEquals(jpeg, pages.get("a", edit.geometryKey))
        assertTrue(pages.contains("a", edit.geometryKey))
        assertNull(pages.get("b", edit.geometryKey))
        assertNull(pages.get("a", edit.copy(rotation = 90).geometryKey))
    }

    @Test
    fun `a new crop, turn or flattening needs a new clean-up while a new look reuses it`() {
        val pages = CleanedPages()
        pages.put("a", edit.geometryKey, jpeg)
        val mesh = PageMesh.of(2, 2, floatArrayOf(0.1f, 0.9f, 0.1f, 0.9f), floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f))

        assertFalse(pages.needsCleaning("a", edit))
        assertFalse(pages.needsCleaning("a", edit.copy(filterStrength = 0.4f, brightness = 0.3f, contrast = -0.2f)))
        assertTrue(pages.needsCleaning("a", edit.copy(rotation = 90)))
        assertTrue(pages.needsCleaning("a", edit.copy(crop = null)))
        assertTrue(pages.needsCleaning("a", edit.copy(flatten = mesh)))
        assertTrue(pages.needsCleaning("b", edit))
    }

    @Test
    fun `turns that end up the same way are the same geometry`() {
        val pages = CleanedPages()
        pages.put("a", edit.copy(rotation = 270).geometryKey, jpeg)
        assertFalse(pages.needsCleaning("a", edit.copy(rotation = -90)))
    }

    @Test
    fun `a page without the Server clean-up look never needs cleaning`() {
        val pages = CleanedPages()
        assertFalse(pages.needsCleaning("a", edit.copy(filter = PageFilters.ENHANCED)))
        assertFalse(pages.needsCleaning("a", PageEdit()))
    }

    @Test
    fun `keeps at most its limit and drops the least recently used first`() {
        val pages = CleanedPages()
        repeat(CleanedPages.MAX_ENTRIES + 3) { index -> pages.put("page$index", edit.geometryKey, jpeg) }

        assertEquals(CleanedPages.MAX_ENTRIES, pages.size)
        assertFalse(pages.contains("page0", edit.geometryKey))
        assertFalse(pages.contains("page2", edit.geometryKey))
        assertTrue(pages.contains("page3", edit.geometryKey))
        assertTrue(pages.contains("page${CleanedPages.MAX_ENTRIES + 2}", edit.geometryKey))
    }

    @Test
    fun `a page that is read stays longer than pages that are not`() {
        val pages = CleanedPages()
        repeat(CleanedPages.MAX_ENTRIES) { index -> pages.put("page$index", edit.geometryKey, jpeg) }
        // The oldest page is used again, so the next oldest goes first.
        pages.get("page0", edit.geometryKey)
        pages.put("new", edit.geometryKey, jpeg)

        assertTrue(pages.contains("page0", edit.geometryKey))
        assertFalse(pages.contains("page1", edit.geometryKey))
    }

    @Test
    fun `a new clean-up for the same page and geometry replaces the old one`() {
        val pages = CleanedPages()
        pages.put("a", edit.geometryKey, jpeg)
        val newer = byteArrayOf(9)
        pages.put("a", edit.geometryKey, newer)

        assertEquals(1, pages.size)
        assertArrayEquals(newer, pages.get("a", edit.geometryKey))
    }

    @Test
    fun `forgetting a page drops all its geometries and tells the views`() {
        val pages = CleanedPages()
        pages.put("a", edit.geometryKey, jpeg)
        pages.put("a", edit.copy(rotation = 90).geometryKey, jpeg)
        pages.put("b", edit.geometryKey, jpeg)
        val before = pages.version.value

        pages.forget("a")

        assertEquals(1, pages.size)
        assertTrue(pages.contains("b", edit.geometryKey))
        assertNotEquals(before, pages.version.value)
    }

    @Test
    fun `keeping a page tells the views, forgetting nothing does not`() {
        val pages = CleanedPages()
        val start = pages.version.value
        pages.put("a", edit.geometryKey, jpeg)
        val kept = pages.version.value
        assertNotEquals(start, kept)

        pages.forget("not kept")
        assertEquals(kept, pages.version.value)
    }
}
