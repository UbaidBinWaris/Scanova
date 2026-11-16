package app.scanova.feature.scan.editor

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.documents.DocumentEditor
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.documents.EditablePage
import app.scanova.core.data.documents.PageDraft
import app.scanova.core.data.model.MarkupStroke
import app.scanova.core.data.model.MarkupTool
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageLayouts
import app.scanova.core.data.model.PagePoint
import app.scanova.core.data.pdf.PdfWriter
import app.scanova.feature.scan.cleanup.CleanedPages
import app.scanova.feature.scan.cleanup.PageCleaner
import app.scanova.feature.scan.filter.ServerCleanup
import app.scanova.feature.scan.geometry.CropRules
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.imaging.DocumentDetector
import app.scanova.feature.scan.navigation.EditorDestination
import app.scanova.feature.scan.ocr.PageTextRecognizer
import app.scanova.feature.scan.render.LivePreviewController
import app.scanova.feature.scan.render.LiveState
import app.scanova.feature.scan.render.MarkupGeometry
import app.scanova.feature.scan.render.PageLook
import app.scanova.feature.scan.render.PageRecipeRenderer
import app.scanova.feature.scan.render.PreviewMaker
import app.scanova.feature.scan.render.RecipeGeometry
import app.scanova.feature.scan.render.geometryKey
import app.scanova.feature.scan.render.look
import app.scanova.feature.scan.render.withLook
import app.scanova.feature.scan.session.CurlExaminer
import app.scanova.feature.scan.session.PageFinisher
import app.scanova.feature.scan.ui.LookMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class EditorTab { IMAGES, MARKUP, PAGE }

internal enum class EditorProblem { LOAD_FAILED, RENDER_FAILED, SAVE_FAILED, PREVIEW_FAILED, FLATTEN_FAILED, CLEANUP_FAILED }

/** One page of the document being edited. */
internal data class EditorPage(
    val source: EditablePage,
    /** The recipe as edited now. */
    val edit: PageEdit,
    /** The recipe the page was opened with: while [edit] equals it, the page keeps its image. */
    val initialEdit: PageEdit,
    val thumbnail: Bitmap?,
    /** The newest staged image and the recipe it was rendered with. */
    val rendered: RenderedPage? = null,
) {
    val id: String get() = source.id

    /** "Flatten" applies: the page has its original to flatten from and is not an app-made ID card page. */
    val canFlatten: Boolean get() = source.hasOriginal && edit.layout != PageLayouts.ID_CARD

    val changed: Boolean get() = edit != initialEdit

    /** The image the page has now matches [edit]. */
    val upToDate: Boolean get() = !changed || rendered?.edit == edit

    /** The page has to be rendered (again); with [retryCleanup], also when its clean-up failed ([ServerCleanup.rendersAgain]). */
    fun needsRender(retryCleanup: Boolean): Boolean = ServerCleanup.rendersAgain(upToDate, rendered?.cleanupMissing == true, retryCleanup)

    /**
     * What the renderer is given: the recipe itself when the page has its
     * original; for a page without one, the recipe applied to its current
     * image, which is already upright and cropped.
     */
    val renderEdit: PageEdit
        get() = if (source.hasOriginal) edit else edit.copy(sourceRotation = 0, crop = null, flatten = null)

    /** Handle of the image the page shows when it is not being edited live. */
    val imageHandle: String get() = rendered?.draft?.image ?: source.image

    /** Width / height of the page as it looks now (approximately, until it is rendered). */
    val aspect: Float
        get() {
            rendered?.takeIf { it.edit.geometryKey == edit.geometryKey }?.let { return it.draft.width.toFloat() / it.draft.height }
            val base = source.width.toFloat() / source.height.coerceAtLeast(1)
            val turns = RecipeGeometry.quarterTurns(edit.rotation - initialEdit.rotation)
            return if (turns % 2 == 1) 1f / base else base
        }
}

internal class RenderedPage(
    val draft: PageDraft,
    val edit: PageEdit,
    /** [edit] asks for Server clean-up but the server could not clean the page: the image has the stand-in look. */
    val cleanupMissing: Boolean = false,
)

/** A page image at preview size, decoded from image [handle]. */
internal class EditorPreview(val handle: String, val image: Bitmap)

/** Cropping the selected page: its original, upright and small, with the outline being dragged. */
internal class CropSession(
    val pageId: String,
    val image: Bitmap,
    val outline: Quad,
    val detecting: Boolean = false,
    /** Auto-detect found no page edges. */
    val nothingFound: Boolean = false,
) {
    fun copy(outline: Quad = this.outline, detecting: Boolean = this.detecting, nothingFound: Boolean = false) =
        CropSession(pageId, image, outline, detecting, nothingFound)
}

internal data class EditorUiState(
    val loading: Boolean = true,
    val pages: List<EditorPage> = emptyList(),
    val selectedPageId: String? = null,
    val previews: Map<String, EditorPreview> = emptyMap(),
    val live: LiveState = LiveState(),
    val tab: EditorTab = EditorTab.IMAGES,
    val lookMode: LookMode = LookMode.FILTERS,
    /** This account can clean pages on its server: the filter row offers Server clean-up. */
    val cleanupAvailable: Boolean = false,
    val markupTool: MarkupTool = MarkupTool.PEN,
    val markupColor: Int = 0,
    val markupWidth: Int = 1,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val crop: CropSession? = null,
    /** Pages the curl model is looking at ("Flatten", or a flattened page's new crop). */
    val flatteningPageIds: Set<String> = emptySet(),
    /** "Done" was tapped while the curl model was still looking at a page: it saves once the model has answered. */
    val finishing: Boolean = false,
    /** Pages waiting for or being rendered. */
    val rendering: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    /** Pages were removed or moved. */
    val reordered: Boolean = false,
    val saving: Boolean = false,
    val saveProgress: Float = 0f,
    val problem: EditorProblem? = null,
    val confirmingDiscard: Boolean = false,
    val confirmingDeleteId: String? = null,
    /** The editor is finished (saved or discarded): the screen closes. */
    val done: Boolean = false,
) {
    val selectedIndex: Int get() = pages.indexOfFirst { it.id == selectedPageId }.coerceAtLeast(0)

    val selected: EditorPage? get() = pages.getOrNull(selectedIndex)

    val hasChanges: Boolean get() = reordered || pages.any { it.changed }
}

/**
 * State holder for the page editor of a saved document.
 *
 * Every change re-renders that page from its original with the full recipe
 * (in the background, a moment after the last change) and stages the result
 * with [DocumentEditor.storeRendered]; the document itself only changes on
 * Done ([DocumentEditor.commit]). Cancel drops everything staged. Originals
 * are never modified.
 *
 * Pages without an original are edited from their current image: the recipe
 * then applies to that image (no crop), and the page is saved without a
 * recipe, since there is no original it could be replayed on.
 *
 * A page with the Server clean-up look is cleaned by the server before it is
 * rendered, once per geometry ([PageCleaner]); when that fails it renders
 * with the stand-in look and says so ([EditorProblem.CLEANUP_FAILED]). An
 * unchanged page keeps its stored image, already cleaned.
 */
@HiltViewModel
internal class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documentEditor: DocumentEditor,
    private val documentRepository: DocumentRepository,
    private val renderer: PageRecipeRenderer,
    private val textRecognizer: PageTextRecognizer,
    previewMaker: PreviewMaker,
    private val dispatchers: AppDispatchers,
    private val clock: TimeSource,
    private val curlExaminer: CurlExaminer,
    private val cleaner: PageCleaner,
    cleanedPages: CleanedPages,
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<EditorDestination>()
    private val documentId = destination.documentId
    private val detector = DocumentDetector()

    private val mutableState = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = mutableState.asStateFlow()

    private var initialOrder: List<String> = emptyList()

    /** Every image staged and not yet discarded, newest per page (main thread). */
    private val staged = mutableMapOf<String, PageDraft>()

    /** Markup undo and redo, per page (main thread). */
    private val history = mutableMapOf<String, MarkupHistory>()

    private val renderQueue = Channel<String>(Channel.UNLIMITED)
    private var renderWorker: Job? = null

    /** Page images being decoded for preview, by handle. */
    private val loadingPreviews = mutableSetOf<String>()

    /** Pages whose failed clean-up is tried again on their next render although nothing changed (main thread). */
    private val cleanupRetries = HashSet<String>()

    private val live = LivePreviewController(viewModelScope, dispatchers, previewMaker, cleanedPages, loadSource = ::loadPreviewSource)

    init {
        viewModelScope.launch { live.state.collect { liveState -> mutableState.update { it.copy(live = liveState) } } }
        viewModelScope.launch { cleaner.available.collect { available -> mutableState.update { it.copy(cleanupAvailable = available) } } }
        renderWorker = viewModelScope.launch { for (pageId in renderQueue) renderPage(pageId) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val loaded = documentEditor.pages(documentId)
            val sources = (loaded as? AppResult.Success)?.value
            if (sources.isNullOrEmpty()) {
                mutableState.update { it.copy(loading = false, problem = EditorProblem.LOAD_FAILED) }
                return@launch
            }
            initialOrder = sources.map { it.id }
            val pages = sources.map { source ->
                // Without an original the stored recipe describes something already burnt into
                // the image: editing starts from the image as it is.
                val initial = if (source.hasOriginal) source.edit ?: PageEdit() else PageEdit()
                EditorPage(source = source, edit = initial, initialEdit = initial, thumbnail = null)
            }
            val start = pages.getOrNull(destination.pageIndex)?.id ?: pages.first().id
            mutableState.update { it.copy(loading = false, pages = pages, selectedPageId = start) }
            refreshSelection()
            loadThumbnails(pages.map { it.id to it.source.image })
        }
    }

    /** Small images of every page for the strip, decoded one after another. */
    private suspend fun loadThumbnails(handles: List<Pair<String, String>>) {
        for ((pageId, handle) in handles) {
            val bytes = (documentEditor.loadImage(handle) as? AppResult.Success)?.value ?: continue
            val thumbnail = withContext(dispatchers.default) { Bitmaps.decodeSampled(bytes, PageFinisher.THUMBNAIL_LONG_EDGE) } ?: continue
            updatePage(pageId) { if (it.thumbnail == null) it.copy(thumbnail = thumbnail) else it }
        }
    }

    // ── Selection and tabs ──────────────────────────────────────────────

    fun onPageSelected(pageId: String) {
        if (mutableState.value.crop != null) return
        mutableState.update { if (it.pages.any { page -> page.id == pageId }) it.copy(selectedPageId = pageId) else it }
        refreshSelection()
    }

    fun onTabSelected(tab: EditorTab) {
        mutableState.update { it.copy(tab = tab) }
    }

    fun onLookModeChange(mode: LookMode) {
        mutableState.update { it.copy(lookMode = mode) }
    }

    private fun refreshSelection() {
        val selected = mutableState.value.selected ?: return
        live.show(selected.id, selected.renderEdit)
        val markup = history[selected.id]
        mutableState.update { it.copy(canUndo = markup?.canUndo == true, canRedo = markup?.canRedo == true) }
    }

    /** The large view of [pageId] is about to be shown: decode its current image unless already done. */
    fun onPreviewNeeded(pageId: String) {
        val page = page(pageId) ?: return
        val handle = page.imageHandle
        if (mutableState.value.previews[pageId]?.handle == handle || handle in loadingPreviews) return
        loadingPreviews += handle
        viewModelScope.launch {
            val bytes = (documentEditor.loadImage(handle) as? AppResult.Success)?.value
            val image = bytes?.let { withContext(dispatchers.default) { Bitmaps.decodeSampled(it, PreviewMaker.BASE_LONG_EDGE) } }
            loadingPreviews -= handle
            if (image == null) return@launch
            mutableState.update { current ->
                val index = current.pages.indexOfFirst { it.id == pageId }
                if (index < 0 || current.pages[index].imageHandle != handle) return@update current
                val kept = current.previews.filterKeys { id ->
                    val other = current.pages.indexOfFirst { it.id == id }
                    other >= 0 && abs(other - current.selectedIndex) <= PREVIEW_NEIGHBOURS
                }
                current.copy(previews = kept + (pageId to EditorPreview(handle, image)))
            }
        }
    }

    // ── Look ────────────────────────────────────────────────────────────

    /** A filter was chosen. Choosing Server clean-up again also retries a clean-up that failed. */
    fun onFilterSelected(filterId: String) {
        val pageId = mutableState.value.selected?.id ?: return
        if (ServerCleanup.isCleanup(filterId) && editable()) retryCleanup(pageId)
        changeSelected { it.withLook(it.look.copy(filterId = filterId)) }
    }

    /** A slider moved: shown live; rendered once the finger lifts ([onLookChangeFinished]). */
    fun onLookChange(look: PageLook) = changeSelected(render = false) { it.withLook(look) }

    fun onLookChangeFinished() {
        mutableState.value.selected?.let { scheduleRender(it.id) }
    }

    fun onApplyLookToAll() {
        val look = mutableState.value.selected?.edit?.look ?: return
        if (!editable()) return
        if (ServerCleanup.isCleanup(look.filterId)) mutableState.value.pages.forEach { retryCleanup(it.id) }
        mutableState.update { state -> state.copy(pages = state.pages.map { it.copy(edit = it.edit.withLook(look)) }) }
        mutableState.value.pages.forEach { scheduleRender(it.id) }
        refreshSelection()
    }

    /** Server clean-up chosen for [pageId]: if its image has the stand-in look of a failed clean-up, it is cleaned again. */
    private fun retryCleanup(pageId: String) {
        if (page(pageId)?.rendered?.cleanupMissing != true) return
        cleanupRetries += pageId
        mutableState.update { it.copy(problem = it.problem.takeUnless { problem -> problem == EditorProblem.CLEANUP_FAILED }) }
    }

    // ── Page ────────────────────────────────────────────────────────────

    /** Turns the page a quarter turn clockwise ([turns] = 1) or anticlockwise (-1); markup turns with it. */
    fun onRotate(turns: Int) {
        val aspect = selectedAspect()
        changeSelected { edit ->
            edit.copy(
                rotation = RecipeGeometry.turn(edit.rotation, turns),
                markup = MarkupGeometry.turn(edit.markup, turns, aspect, 1f),
            )
        }
    }

    fun onMovePage(pageId: String, offset: Int) {
        if (!editable()) return
        mutableState.update { state ->
            val from = state.pages.indexOfFirst { it.id == pageId }
            val to = from + offset
            if (from < 0 || to !in state.pages.indices) return@update state
            val pages = state.pages.toMutableList().also { it.add(to, it.removeAt(from)) }
            state.copy(pages = pages, reordered = pages.map { it.id } != initialOrder)
        }
    }

    fun onDeleteRequested(pageId: String) {
        if (mutableState.value.pages.size > 1 && editable()) mutableState.update { it.copy(confirmingDeleteId = pageId) }
    }

    fun onDeleteDismissed() {
        mutableState.update { it.copy(confirmingDeleteId = null) }
    }

    fun onDeleteConfirmed() {
        val pageId = mutableState.value.confirmingDeleteId ?: return
        mutableState.update { state ->
            if (state.pages.size <= 1) return@update state.copy(confirmingDeleteId = null)
            val index = state.pages.indexOfFirst { it.id == pageId }
            val pages = state.pages.filterNot { it.id == pageId }
            val selected = if (state.selectedPageId == pageId) pages[index.coerceIn(0, pages.lastIndex)].id else state.selectedPageId
            state.copy(confirmingDeleteId = null, pages = pages, selectedPageId = selected, reordered = true)
        }
        live.forget(pageId)
        history.remove(pageId)
        // Its staged image will never be committed: drop it now.
        staged.remove(pageId)?.let { draft -> viewModelScope.launch { documentEditor.discard(listOf(draft)) } }
        refreshSelection()
    }

    // ── Crop ────────────────────────────────────────────────────────────

    fun onCropStart() {
        val page = mutableState.value.selected ?: return
        // Not while the curl model is looking at this page: its answer belongs to the current outline.
        if (!page.source.hasOriginal || !editable() || page.id in mutableState.value.flatteningPageIds) return
        viewModelScope.launch {
            val upright = uprightOriginal(page)
            if (upright == null) {
                mutableState.update { it.copy(problem = EditorProblem.PREVIEW_FAILED) }
                return@launch
            }
            val outline = RecipeGeometry.cropQuad(page.edit.crop) ?: RecipeGeometry.WHOLE
            mutableState.update { it.copy(crop = CropSession(page.id, upright, outline)) }
        }
    }

    /** [page]'s original turned upright, at most [CROP_LONG_EDGE] on its longer side; null when it cannot be read. */
    private suspend fun uprightOriginal(page: EditorPage): Bitmap? {
        val source = live.source(page.id) ?: return null
        return withContext(dispatchers.default) {
            try {
                renderer.geometry(source, PageEdit(sourceRotation = page.edit.sourceRotation), CROP_LONG_EDGE)
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }

    // ── Flatten ─────────────────────────────────────────────────────────

    /**
     * "Flatten": a flattened page goes back to its crop outline; any other page
     * (with an original, not an ID card) is flattened along the curl model's
     * mesh, which the model works out now for the page's current outline.
     */
    fun onFlattenToggled() {
        val page = mutableState.value.selected ?: return
        if (!page.canFlatten || !editable() || page.id in mutableState.value.flatteningPageIds) return
        if (page.edit.flatten != null) {
            changePage(page.id) { it.copy(flatten = null) }
            return
        }
        examineCurl(page.id, reportFailure = true)
    }

    /**
     * Asks the curl model about page [pageId] as it is outlined now, and gives
     * the page the mesh it finds. The answer is dropped when the page's crop
     * changed meanwhile (it belongs to the old outline), when the page was
     * deleted, or when it already has a mesh again. With [reportFailure], no
     * mesh says so ([EditorProblem.FLATTEN_FAILED]).
     */
    private fun examineCurl(pageId: String, reportFailure: Boolean) {
        val page = page(pageId) ?: return
        if (pageId in mutableState.value.flatteningPageIds) return
        val askedCrop = page.edit.crop
        mutableState.update { it.copy(flatteningPageIds = it.flatteningPageIds + pageId) }
        viewModelScope.launch {
            try {
                val upright = uprightOriginal(page)
                val mesh = upright?.let {
                    withContext(dispatchers.default) {
                        try {
                            curlExaminer.examine(it, RecipeGeometry.cropQuad(askedCrop)).mesh
                        } finally {
                            it.recycle()
                        }
                    }
                }
                val current = page(pageId)?.edit
                if (current != null && current.crop == askedCrop && current.flatten == null) {
                    if (mesh != null) {
                        changePage(pageId) { it.copy(flatten = mesh) }
                    } else if (reportFailure) {
                        val problem = if (upright == null) EditorProblem.PREVIEW_FAILED else EditorProblem.FLATTEN_FAILED
                        mutableState.update { it.copy(problem = problem) }
                    }
                }
            } finally {
                mutableState.update { it.copy(flatteningPageIds = it.flatteningPageIds - pageId) }
            }
        }
    }

    fun onCropCornerMoved(index: Int, position: Point2) {
        mutableState.update { state ->
            val crop = state.crop ?: return@update state
            val moved = CropRules.moveCorner(crop.outline, index, position) ?: return@update state
            state.copy(crop = crop.copy(outline = moved))
        }
    }

    fun onCropCornerNudged(index: Int, dx: Double, dy: Double) {
        val corner = mutableState.value.crop?.outline?.corners?.getOrNull(index) ?: return
        onCropCornerMoved(index, Point2(corner.x + dx, corner.y + dy))
    }

    fun onCropAutoDetect() {
        val crop = mutableState.value.crop ?: return
        if (crop.detecting) return
        mutableState.update { it.copy(crop = crop.copy(detecting = true)) }
        viewModelScope.launch {
            val detected = withContext(dispatchers.default) { detector.detect(crop.image) }
            mutableState.update { state ->
                val current = state.crop ?: return@update state
                state.copy(crop = current.copy(outline = detected ?: current.outline, detecting = false, nothingFound = detected == null))
            }
        }
    }

    fun onCropWholePage() {
        mutableState.update { state -> state.crop?.let { state.copy(crop = it.copy(outline = RecipeGeometry.WHOLE)) } ?: state }
    }

    fun onCropCancel() {
        mutableState.update { it.copy(crop = null) }
    }

    fun onCropApply() {
        val crop = mutableState.value.crop ?: return
        val outline = crop.outline.takeUnless { CropRules.isWholeImage(it) }
        mutableState.update { it.copy(crop = null) }
        val page = page(crop.pageId) ?: return
        val newCrop = RecipeGeometry.toCrop(outline)
        if (newCrop == page.edit.crop) return
        // The crop applies at once. A flattened page's mesh belongs to the old outline:
        // the page shows its new crop flat by the outline until the model has looked again.
        val wasFlattened = page.edit.flatten != null
        changePage(crop.pageId) { it.copy(crop = newCrop, flatten = null) }
        if (wasFlattened) examineCurl(crop.pageId, reportFailure = false)
    }

    // ── Markup ──────────────────────────────────────────────────────────

    fun onMarkupToolSelected(tool: MarkupTool) {
        mutableState.update { it.copy(markupTool = tool, markupColor = 0) }
    }

    fun onMarkupColorSelected(index: Int) {
        mutableState.update { it.copy(markupColor = index) }
    }

    fun onMarkupWidthSelected(index: Int) {
        mutableState.update { it.copy(markupWidth = index) }
    }

    /** A finished stroke on the selected page (points in fractions of the page). */
    fun onStroke(points: List<PagePoint>) {
        if (points.isEmpty()) return
        val state = mutableState.value
        val stroke = MarkupStroke(
            tool = state.markupTool,
            color = MarkupPalette.colors(state.markupTool)[state.markupColor.coerceIn(0, MarkupPalette.colors(state.markupTool).lastIndex)],
            width = MarkupPalette.widths(state.markupTool)[state.markupWidth.coerceIn(0, MarkupPalette.widths(state.markupTool).lastIndex)],
            points = points,
        )
        changeMarkup { it + stroke }
    }

    fun onUndo() {
        val pageId = mutableState.value.selected?.id ?: return
        val previous = history[pageId]?.undo(currentMarkup(pageId)) ?: return
        setMarkup(pageId, previous)
    }

    fun onRedo() {
        val pageId = mutableState.value.selected?.id ?: return
        val next = history[pageId]?.redo(currentMarkup(pageId)) ?: return
        setMarkup(pageId, next)
    }

    fun onClearMarkup() {
        if (currentMarkup(mutableState.value.selected?.id ?: return).isEmpty()) return
        changeMarkup { emptyList() }
    }

    private fun currentMarkup(pageId: String): List<MarkupStroke> = page(pageId)?.edit?.markup.orEmpty()

    private fun changeMarkup(change: (List<MarkupStroke>) -> List<MarkupStroke>) {
        val pageId = mutableState.value.selected?.id ?: return
        if (!editable()) return
        val before = currentMarkup(pageId)
        history.getOrPut(pageId) { MarkupHistory() }.record(before)
        setMarkup(pageId, change(before))
    }

    private fun setMarkup(pageId: String, markup: List<MarkupStroke>) {
        changePage(pageId) { it.copy(markup = markup) }
        val entry = history[pageId]
        mutableState.update { it.copy(canUndo = entry?.canUndo == true, canRedo = entry?.canRedo == true) }
    }

    // ── Done / cancel ───────────────────────────────────────────────────

    fun onCancel() {
        val state = mutableState.value
        if (state.saving) return
        if (state.crop != null) {
            onCropCancel()
            return
        }
        if (state.hasChanges) {
            mutableState.update { it.copy(confirmingDiscard = true) }
        } else {
            discardAndClose()
        }
    }

    fun onDiscardDismissed() {
        mutableState.update { it.copy(confirmingDiscard = false) }
    }

    fun onDiscardConfirmed() {
        mutableState.update { it.copy(confirmingDiscard = false) }
        discardAndClose()
    }

    private fun discardAndClose() {
        viewModelScope.launch {
            // Nothing new may be staged once the drafts are dropped.
            renderWorker?.cancelAndJoin()
            documentEditor.discard(staged.values.toList())
            staged.clear()
            mutableState.update { it.copy(done = true) }
        }
    }

    fun onDone() {
        val current = mutableState.value
        if (current.saving || current.loading || current.done || current.finishing) return
        if (current.crop != null) {
            onCropApply()
        }
        if (mutableState.value.flatteningPageIds.isNotEmpty()) {
            // The curl model is still deciding a page's flattening: save once it has answered, not without it.
            mutableState.update { it.copy(finishing = true) }
            viewModelScope.launch {
                mutableState.first { it.flatteningPageIds.isEmpty() }
                mutableState.update { it.copy(finishing = false) }
                onDone()
            }
            return
        }
        if (!mutableState.value.hasChanges) {
            discardAndClose()
            return
        }
        mutableState.update { it.copy(saving = true, saveProgress = 0f, problem = null) }
        viewModelScope.launch {
            // Changes made a moment ago are rendered first.
            mutableState.value.pages.filterNot { it.upToDate }.forEach { scheduleRender(it.id, immediately = true) }
            mutableState.first { state -> state.rendering.isEmpty() }
            val pages = mutableState.value.pages
            if (pages.any { !it.upToDate }) {
                mutableState.update { it.copy(saving = false, problem = EditorProblem.RENDER_FAILED) }
                return@launch
            }
            val drafts = pages.map { page ->
                if (page.changed) {
                    checkNotNull(page.rendered).draft
                } else {
                    PageDraft(page.id, page.source.image, page.source.width, page.source.height, page.source.ocrText, page.source.edit)
                }
            }
            val document = documentRepository.observeDocument(documentId).firstOrNull()
            val capturedAt = document?.capturedAt ?: clock.now()
            val steps = drafts.size + 1f
            val result = documentEditor.commit(documentId, drafts) { output, readPage ->
                val writer = PdfWriter(output, capturedAt, document?.title)
                drafts.forEachIndexed { index, draft ->
                    writer.addJpegPage(readPage(draft.id))
                    mutableState.update { it.copy(saveProgress = (index + 1) / steps) }
                }
                writer.finish()
            }
            when (result) {
                is AppResult.Success -> {
                    renderWorker?.cancelAndJoin()
                    // Images staged for pages that ended up unchanged were not used.
                    val used = drafts.map { it.image }.toSet()
                    documentEditor.discard(staged.values.filter { it.image !in used })
                    staged.clear()
                    mutableState.update { it.copy(saving = false, saveProgress = 1f, done = true) }
                }
                is AppResult.Failure -> mutableState.update {
                    it.copy(saving = false, saveProgress = 0f, problem = EditorProblem.SAVE_FAILED)
                }
            }
        }
    }

    fun onProblemShown() {
        mutableState.update { it.copy(problem = null) }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private fun changeSelected(render: Boolean = true, change: (PageEdit) -> PageEdit) {
        val pageId = mutableState.value.selected?.id ?: return
        changePage(pageId, render, change)
    }

    private fun changePage(pageId: String, render: Boolean = true, change: (PageEdit) -> PageEdit) {
        if (!editable()) return
        updatePage(pageId) { it.copy(edit = change(it.edit)) }
        if (render) scheduleRender(pageId)
        if (pageId == mutableState.value.selectedPageId) refreshSelection()
    }

    private fun scheduleRender(pageId: String, immediately: Boolean = false) {
        val page = page(pageId) ?: return
        if (!page.needsRender(retryCleanup = pageId in cleanupRetries)) return
        var added = false
        mutableState.update { state ->
            added = pageId !in state.rendering
            state.copy(rendering = state.rendering + pageId, failed = state.failed - pageId)
        }
        if (added) renderQueue.trySend(if (immediately) IMMEDIATE_PREFIX + pageId else pageId)
    }

    /** Renders [entry] from its original and stages it; goes round again if the page changed meanwhile. */
    private suspend fun renderPage(entry: String) {
        val immediately = entry.startsWith(IMMEDIATE_PREFIX)
        val pageId = entry.removePrefix(IMMEDIATE_PREFIX)
        // Let a burst of changes (dragging corners, several taps) settle first.
        if (!immediately) delay(RENDER_DEBOUNCE_MILLIS)
        var failed = false
        try {
            repeat(MAX_ROUNDS) {
                val page = page(pageId) ?: return
                if (!page.needsRender(retryCleanup = cleanupRetries.remove(pageId))) return
                if (!renderOnce(page)) {
                    failed = true
                    return
                }
            }
        } finally {
            mutableState.update { state ->
                state.copy(rendering = state.rendering - pageId, failed = if (failed) state.failed + pageId else state.failed)
            }
            if (!failed && page(pageId)?.upToDate == false) scheduleRender(pageId, immediately = true)
        }
    }

    private suspend fun renderOnce(page: EditorPage): Boolean {
        val edit = page.edit
        val target = page.renderEdit
        val bytes = loadSourceBytes(page) ?: return false
        // Text depends on what is on the page: read it again only when the geometry changed.
        val readText = edit.geometryKey != page.initialEdit.geometryKey
        // Server clean-up: the page's cleaned layer for this geometry, asked of the server first when none is kept.
        val wantsCleanup = ServerCleanup.isCleanup(target.filter)
        val cleaned = if (wantsCleanup) {
            cleaner.layer(page.id, target, PageFinisher.PAGE_LONG_EDGE) { flatPage(bytes, target) }
        } else {
            null
        }
        val output = try {
            withContext(dispatchers.default) {
                val source = Bitmaps.decodeWithin(bytes, RENDER_SOURCE_LONG_EDGE, MIN_RENDER_SOURCE_LONG_EDGE) ?: return@withContext null
                var rendered: PageRecipeRenderer.Rendered? = null
                try {
                    rendered = renderer.render(source, target, PageFinisher.PAGE_LONG_EDGE, keepFlat = readText, cleaned = cleaned)
                    source.recycle()
                    val image = rendered.page
                    val text = if (readText) rendered.flat?.let { textRecognizer.recognize(it) } else null
                    RenderOutput(
                        jpeg = Bitmaps.toJpeg(image, PageFinisher.PAGE_JPEG_QUALITY),
                        width = image.width,
                        height = image.height,
                        thumbnail = Bitmaps.thumbnail(image, PageFinisher.THUMBNAIL_LONG_EDGE),
                        text = text,
                    )
                } catch (e: OutOfMemoryError) {
                    null
                } finally {
                    if (!source.isRecycled) source.recycle()
                    rendered?.recycle()
                }
            }
        } finally {
            cleaned?.recycle()
        } ?: return false
        val cleanupMissing = wantsCleanup && cleaned == null
        if (cleanupMissing) mutableState.update { it.copy(problem = EditorProblem.CLEANUP_FAILED) }
        // Stored and recorded together, so a staged image is never lost track of (and discarded later).
        return withContext(NonCancellable) {
            val handle = when (val stored = documentEditor.storeRendered(page.id, output.jpeg)) {
                is AppResult.Success -> stored.value
                is AppResult.Failure -> return@withContext false
            }
            val draft = PageDraft(
                id = page.id,
                image = handle,
                width = output.width,
                height = output.height,
                ocrText = output.text?.takeIf { it.isNotBlank() } ?: page.source.ocrText,
                // A recipe only means something next to an original it can be replayed on.
                edit = if (page.source.hasOriginal) edit else null,
            )
            val stillHere = page(page.id) != null
            val previous = if (stillHere) staged.put(page.id, draft) else null
            listOfNotNull(previous, draft.takeUnless { stillHere }).takeIf { it.isNotEmpty() }?.let { documentEditor.discard(it) }
            if (stillHere) {
                updatePage(page.id) { it.copy(rendered = RenderedPage(draft, edit, cleanupMissing), thumbnail = output.thumbnail) }
            }
            true
        }
    }

    private class RenderOutput(val jpeg: ByteArray, val width: Int, val height: Int, val thumbnail: Bitmap, val text: String?)

    /** [target]'s geometry alone (no look) from the page's source [bytes], at page size: what Server clean-up sends. */
    private fun flatPage(bytes: ByteArray, target: PageEdit): Bitmap? {
        val source = Bitmaps.decodeWithin(bytes, RENDER_SOURCE_LONG_EDGE, MIN_RENDER_SOURCE_LONG_EDGE) ?: return null
        return try {
            renderer.geometry(source, target, PageFinisher.PAGE_LONG_EDGE)
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            source.recycle()
        }
    }

    /** The page's source (original, or current image without one), decoded at preview size. */
    private suspend fun loadPreviewSource(pageId: String): Bitmap? {
        val page = page(pageId) ?: return null
        val bytes = loadSourceBytes(page) ?: return null
        return withContext(dispatchers.default) { Bitmaps.decodeSampled(bytes, PreviewMaker.SOURCE_LONG_EDGE) }
    }

    private suspend fun loadSourceBytes(page: EditorPage): ByteArray? {
        val loaded = if (page.source.hasOriginal) documentEditor.loadOriginal(page.id) else documentEditor.loadImage(page.source.image)
        return (loaded as? AppResult.Success)?.value
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun page(pageId: String): EditorPage? = mutableState.value.pages.firstOrNull { it.id == pageId }

    private fun updatePage(pageId: String, change: (EditorPage) -> EditorPage) {
        mutableState.update { state -> state.copy(pages = state.pages.map { if (it.id == pageId) change(it) else it }) }
    }

    private fun editable(): Boolean = mutableState.value.let { !it.saving && !it.loading && !it.done }

    /** Width / height of the selected page as shown now. */
    private fun selectedAspect(): Float {
        val state = mutableState.value
        val page = state.selected ?: return 1f
        val layers = state.live.layers?.takeIf { it.pageId == page.id && it.geometry == page.renderEdit.geometryKey }
        return layers?.let { it.base.width.toFloat() / it.base.height } ?: page.aspect
    }

    private companion object {
        /** Previews kept on each side of the selected page, for smooth swiping. */
        const val PREVIEW_NEIGHBOURS = 1
        const val RENDER_DEBOUNCE_MILLIS = 400L
        const val MAX_ROUNDS = 3
        const val IMMEDIATE_PREFIX = "!"

        /** Originals can be 12–50 MP: decoded at most this large for rendering, smaller when memory is short. */
        const val RENDER_SOURCE_LONG_EDGE = 4096
        const val MIN_RENDER_SOURCE_LONG_EDGE = 1600
        const val CROP_LONG_EDGE = 1600
    }
}

/** Undo and redo of one page's markup, as snapshots of its strokes. */
internal class MarkupHistory {
    private val undoStack = ArrayDeque<List<MarkupStroke>>()
    private val redoStack = ArrayDeque<List<MarkupStroke>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** [before] is about to change: remember it, and forget what could be redone. */
    fun record(before: List<MarkupStroke>) {
        undoStack.addLast(before)
        if (undoStack.size > LIMIT) undoStack.removeFirst()
        redoStack.clear()
    }

    /** The strokes before the last change, or null; [current] becomes redoable. */
    fun undo(current: List<MarkupStroke>): List<MarkupStroke>? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return previous
    }

    fun redo(current: List<MarkupStroke>): List<MarkupStroke>? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    private companion object {
        const val LIMIT = 50
    }
}
