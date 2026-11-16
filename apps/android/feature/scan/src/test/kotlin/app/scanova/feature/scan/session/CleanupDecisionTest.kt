package app.scanova.feature.scan.session

import app.scanova.core.data.documents.EditablePage
import app.scanova.core.data.documents.PageDraft
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageFilters
import app.scanova.feature.scan.cleanup.CleanedPages
import app.scanova.feature.scan.editor.EditorPage
import app.scanova.feature.scan.editor.RenderedPage
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.render.FlattenChoice
import app.scanova.feature.scan.render.PageLook
import app.scanova.feature.scan.render.geometryKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the scan's processor and the editor ask the server to clean a page before rendering it. */
class CleanupDecisionTest {

    private val outline = Quad(Point2(0.1, 0.2), Point2(0.9, 0.1), Point2(0.85, 0.9), Point2(0.15, 0.95))

    private fun draft(filterId: String = PageFilters.SERVER_CLEANUP) = DraftPage(
        id = "page",
        outline = outline,
        // The flattening model is not part of this decision.
        flatten = FlattenChoice.OFF,
        detectedOutline = null,
        look = PageLook(filterId = filterId),
        sourceRotation = 90,
        captureWidth = 1920,
        captureHeight = 2560,
        finished = null,
    )

    @Test
    fun `a Server clean-up page needs cleaning while nothing is kept for its current geometry`() {
        val pages = CleanedPages()
        val page = draft()
        assertTrue(pages.needsCleaning(page.id, page.toWorkingEdit()))

        pages.put(page.id, page.toWorkingEdit().geometryKey, byteArrayOf(1))
        assertFalse(pages.needsCleaning(page.id, page.toWorkingEdit()))
    }

    @Test
    fun `turning or recropping the page needs a new clean-up, adjusting its look does not`() {
        val pages = CleanedPages()
        val page = draft()
        pages.put(page.id, page.toWorkingEdit().geometryKey, byteArrayOf(1))

        assertTrue(pages.needsCleaning(page.id, page.copy(rotation = 90).toWorkingEdit()))
        assertTrue(pages.needsCleaning(page.id, page.copy(outline = Quad.inset(1.0, 1.0, 0.1)).toWorkingEdit()))
        val adjusted = page.copy(look = page.look.copy(strength = 0.5f, brightness = 0.2f, contrast = 0.1f))
        assertFalse(pages.needsCleaning(page.id, adjusted.toWorkingEdit()))
    }

    @Test
    fun `pages with other looks are never sent to the server`() {
        val pages = CleanedPages()
        listOf(PageFilters.ORIGINAL, PageFilters.ENHANCED, PageFilters.GREYSCALE).forEach { filterId ->
            assertFalse(pages.needsCleaning("page", draft(filterId).toWorkingEdit()))
        }
    }

    @Test
    fun `a page never rendered is rendered, retry or not`() {
        assertTrue(draft().needsRender(retryCleanup = false))
        assertTrue(draft().needsRender(retryCleanup = true))
    }

    // ── Editor ──────────────────────────────────────────────────────────

    private val source = EditablePage(
        id = "page",
        width = 1000,
        height = 1400,
        ocrText = "text",
        hasOriginal = true,
        edit = PageEdit(),
        image = "pages/page.jpg",
    )

    private fun rendered(edit: PageEdit, cleanupMissing: Boolean) =
        RenderedPage(PageDraft("page", "staged/page.jpg", 1000, 1400, "text", edit), edit, cleanupMissing)

    @Test
    fun `an edited page whose clean-up failed is rendered again only when Server clean-up is chosen again`() {
        val edit = PageEdit(filter = PageFilters.SERVER_CLEANUP)
        val page = EditorPage(source, edit = edit, initialEdit = PageEdit(), thumbnail = null, rendered = rendered(edit, cleanupMissing = true))

        assertTrue(page.upToDate)
        assertFalse(page.needsRender(retryCleanup = false))
        assertTrue(page.needsRender(retryCleanup = true))
    }

    @Test
    fun `a cleaned page or an unchanged page is not sent again for choosing it again`() {
        val edit = PageEdit(filter = PageFilters.SERVER_CLEANUP)
        val cleaned = EditorPage(source, edit = edit, initialEdit = PageEdit(), thumbnail = null, rendered = rendered(edit, cleanupMissing = false))
        assertFalse(cleaned.needsRender(retryCleanup = true))

        // Reopened with Server clean-up already: it keeps its stored image while unchanged.
        val unchanged = EditorPage(source, edit = edit, initialEdit = edit, thumbnail = null)
        assertFalse(unchanged.needsRender(retryCleanup = true))
        // Any change renders it, cleaning it again first.
        assertTrue(unchanged.copy(edit = edit.copy(brightness = 0.2f)).needsRender(retryCleanup = false))
    }
}
