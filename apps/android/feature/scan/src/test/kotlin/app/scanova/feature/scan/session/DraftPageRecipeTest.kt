package app.scanova.feature.scan.session

import app.scanova.core.data.documents.EditablePage
import app.scanova.core.data.model.MarkupStroke
import app.scanova.core.data.model.MarkupTool
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageFilters
import app.scanova.core.data.model.PageLayouts
import app.scanova.core.data.model.PageMesh
import app.scanova.core.data.model.PagePoint
import app.scanova.feature.scan.editor.EditorPage
import app.scanova.feature.scan.editor.MarkupHistory
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.render.FlattenChoice
import app.scanova.feature.scan.render.PageFlattening
import app.scanova.feature.scan.render.PageLook
import app.scanova.feature.scan.render.geometryOnly
import app.scanova.feature.scan.render.look
import app.scanova.feature.scan.render.withLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How pages of a scan and of the editor turn into recipes. */
class DraftPageRecipeTest {

    private val outline = Quad(Point2(0.1, 0.2), Point2(0.9, 0.1), Point2(0.85, 0.9), Point2(0.15, 0.95))

    private fun draft(outline: Quad? = this.outline, rotation: Int = 0, sourceRotation: Int = 90) = DraftPage(
        id = "page",
        outline = outline,
        detectedOutline = null,
        look = PageLook(filterId = "magic_color", strength = 0.6f, brightness = 0.1f, contrast = -0.2f),
        rotation = rotation,
        sourceRotation = sourceRotation,
        captureWidth = 1920,
        captureHeight = 2560,
        finished = null,
    )

    @Test
    fun `a scanned page records everything needed to render it again from its original`() {
        val edit = draft(rotation = -90).toEdit()
        assertEquals(90, edit.sourceRotation)
        assertEquals(270, edit.rotation)
        assertEquals(outline.corners.map { PagePoint(it.x.toFloat(), it.y.toFloat()) }, edit.crop)
        assertEquals("magic_color", edit.filter)
        assertEquals(0.6f, edit.filterStrength)
        assertEquals(0.1f, edit.brightness)
        assertEquals(-0.2f, edit.contrast)
        assertTrue(edit.markup.isEmpty())
    }

    @Test
    fun `the scan renders from its upright working copy`() {
        val draft = draft()
        assertEquals(0, draft.toWorkingEdit().sourceRotation)
        assertEquals(draft.toEdit().copy(sourceRotation = 0), draft.toWorkingEdit())
    }

    @Test
    fun `a page without an outline is the whole image`() {
        assertNull(draft(outline = null).toEdit().crop)
    }

    @Test
    fun `a page never rendered is not up to date`() {
        assertFalse(draft().isUpToDate)
    }

    @Test
    fun `every change of look or turn changes the recipe the image must match`() {
        val draft = draft()
        assertTrue(draft.toWorkingEdit() != draft.copy(look = draft.look.copy(strength = 1f)).toWorkingEdit())
        assertTrue(draft.toWorkingEdit() != draft.copy(rotation = 90).toWorkingEdit())
        assertEquals(draft.toWorkingEdit(), draft.copy(rotation = 360).toWorkingEdit())
    }

    @Test
    fun `look and recipe convert both ways`() {
        val look = PageLook(PageFilters.GREYSCALE, 0.4f, -0.5f, 0.3f)
        val edit = PageEdit(rotation = 90).withLook(look)
        assertEquals(look, edit.look)
        assertEquals(90, edit.rotation)
        assertEquals(PageEdit(rotation = 90), edit.geometryOnly())
        // An id this version does not know is shown (and kept as) the original look.
        assertEquals(PageFilters.ORIGINAL, PageEdit(filter = "from_the_future").look.filterId)
    }

    // ── Editor ──────────────────────────────────────────────────────────

    private fun editable(hasOriginal: Boolean, edit: PageEdit? = null) = EditablePage(
        id = "page",
        width = 1000,
        height = 1400,
        ocrText = "text",
        hasOriginal = hasOriginal,
        edit = edit,
        image = "pages/page.jpg",
    )

    @Test
    fun `a page with its original renders the whole recipe`() {
        val edit = PageEdit(sourceRotation = 90, crop = draft().toEdit().crop, rotation = 180, filter = PageFilters.ENHANCED)
        val page = EditorPage(editable(hasOriginal = true, edit), edit = edit, initialEdit = edit, thumbnail = null)
        assertEquals(edit, page.renderEdit)
        assertFalse(page.changed)
        assertTrue(page.upToDate)
    }

    @Test
    fun `a page without an original renders from its current image, uncropped`() {
        val edit = PageEdit(sourceRotation = 90, crop = draft().toEdit().crop, rotation = 90, filter = PageFilters.GREYSCALE)
        val page = EditorPage(editable(hasOriginal = false), edit = edit, initialEdit = PageEdit(), thumbnail = null)
        assertEquals(0, page.renderEdit.sourceRotation)
        assertNull(page.renderEdit.crop)
        assertEquals(90, page.renderEdit.rotation)
        assertEquals(PageFilters.GREYSCALE, page.renderEdit.filter)
        assertTrue(page.changed)
        assertFalse(page.upToDate)
    }

    @Test
    fun `a page turned sideways reports the turned shape until it is rendered`() {
        val page = EditorPage(editable(hasOriginal = true), edit = PageEdit(rotation = 90), initialEdit = PageEdit(), thumbnail = null)
        assertEquals(1400f / 1000f, page.aspect, 1e-6f)
    }

    // ── Flattening ──────────────────────────────────────────────────────

    private val mesh = PageMesh.of(2, 2, floatArrayOf(0.1f, 0.9f, 0.1f, 0.9f), floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f))

    @Test
    fun `the keep rule is off never, on always, automatic only for a bent page`() {
        assertNull(PageFlattening.keep(FlattenChoice.OFF, mesh, 1.0))
        assertEquals(mesh, PageFlattening.keep(FlattenChoice.ON, mesh, 0.0))
        assertEquals(mesh, PageFlattening.keep(FlattenChoice.AUTO, mesh, PageFlattening.BENT))
        assertNull(PageFlattening.keep(FlattenChoice.AUTO, mesh, PageFlattening.BENT - 1e-6))
        assertNull(PageFlattening.keep(FlattenChoice.AUTO, null, Double.NaN))
        assertNull(PageFlattening.keep(FlattenChoice.AUTO, mesh, Double.NaN))
    }

    @Test
    fun `a new document page asks the model once, about its outline`() {
        val page = draft()
        assertTrue(page.needsFlattening)
        assertFalse(page.isUpToDate)
        val answered = page.copy(flattening = Flattening(outline, mesh, bend = 0.01))
        assertFalse(answered.needsFlattening)
        assertEquals(mesh, answered.mesh)
        assertEquals(mesh, answered.toEdit().flatten)
        assertTrue(answered.isFlattened)
    }

    @Test
    fun `a flat page keeps its outline, and turning Flatten on uses the mesh after all`() {
        val flat = draft().copy(flattening = Flattening(outline, mesh, bend = 0.001))
        assertNull(flat.mesh)
        assertFalse(flat.needsFlattening)
        assertEquals(mesh, flat.copy(flatten = FlattenChoice.ON).mesh)
        assertNull(flat.copy(flatten = FlattenChoice.OFF).mesh)
    }

    @Test
    fun `a new outline asks again, and the old answer is never applied to it`() {
        val answered = draft().copy(flattening = Flattening(outline, mesh, bend = 0.01))
        val recropped = answered.copy(outline = Quad.inset(1.0, 1.0, 0.1))
        assertTrue(recropped.needsFlattening)
        assertNull(recropped.mesh)
        assertNull(recropped.toEdit().flatten)
    }

    @Test
    fun `a page kept whole is left alone unless the user asks`() {
        val whole = draft(outline = null)
        assertFalse(whole.needsFlattening)
        assertNull(whole.mesh)
        val asked = whole.copy(flatten = FlattenChoice.ON)
        assertTrue(asked.needsFlattening)
        val answered = asked.copy(flattening = Flattening(null, mesh, bend = 0.002))
        assertFalse(answered.needsFlattening)
        assertEquals(mesh, answered.mesh)
    }

    @Test
    fun `switched off or an ID card, the model is never asked`() {
        assertFalse(draft().copy(flatten = FlattenChoice.OFF).needsFlattening)
        val card = draft().copy(mode = CameraMode.ID_CARD)
        assertFalse(card.needsFlattening)
        assertNull(card.copy(flatten = FlattenChoice.ON, flattening = Flattening(outline, mesh, 1.0)).mesh)
        assertEquals(PageLayouts.ID_CARD, card.toEdit().layout)
        assertNull(draft().toEdit().layout)
    }

    @Test
    fun `no answer from the model is an answer, so the page renders by its outline`() {
        val failed = draft().copy(flattening = Flattening(outline, null, Double.NaN))
        assertFalse(failed.needsFlattening)
        assertNull(failed.mesh)
    }

    @Test
    fun `the editor offers Flatten for pages with an original that are not ID cards`() {
        val plain = EditorPage(editable(hasOriginal = true), edit = PageEdit(), initialEdit = PageEdit(), thumbnail = null)
        assertTrue(plain.canFlatten)
        val card = PageEdit(layout = PageLayouts.ID_CARD)
        assertFalse(EditorPage(editable(hasOriginal = true, card), edit = card, initialEdit = card, thumbnail = null).canFlatten)
        assertFalse(EditorPage(editable(hasOriginal = false), edit = PageEdit(), initialEdit = PageEdit(), thumbnail = null).canFlatten)
        // Without an original there is nothing to flatten from: the mesh is dropped.
        val flattened = PageEdit(flatten = mesh)
        assertNull(EditorPage(editable(hasOriginal = false), edit = flattened, initialEdit = PageEdit(), thumbnail = null).renderEdit.flatten)
    }

    @Test
    fun `markup undo and redo walk through the history`() {
        val stroke = MarkupStroke(MarkupTool.PEN, 0xFF000000, 0.005f, listOf(PagePoint(0.5f, 0.5f)))
        val history = MarkupHistory()
        assertFalse(history.canUndo)

        history.record(emptyList())
        val one = listOf(stroke)
        history.record(one)
        val two = listOf(stroke, stroke)

        assertEquals(one, history.undo(two))
        assertEquals(emptyList<MarkupStroke>(), history.undo(one))
        assertNull(history.undo(emptyList()))
        assertEquals(one, history.redo(emptyList()))
        assertEquals(two, history.redo(one))
        assertNull(history.redo(two))

        // A new change after undoing forgets what could be redone.
        history.undo(two)
        history.record(one)
        assertFalse(history.canRedo)
    }
}
