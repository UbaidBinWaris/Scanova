package app.scanova.feature.scan.review

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.pdf.PdfWriter
import app.scanova.core.data.scan.ScanRepository
import app.scanova.core.data.scan.ScannedDocument
import app.scanova.core.data.scan.ScannedPage
import app.scanova.feature.scan.R
import app.scanova.feature.scan.cleanup.CleanedPages
import app.scanova.feature.scan.cleanup.PageCleaner
import app.scanova.feature.scan.filter.ServerCleanup
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.ocr.TitleSuggester
import app.scanova.feature.scan.imaging.PageFlattener
import app.scanova.feature.scan.render.FlattenChoice
import app.scanova.feature.scan.render.LivePreviewController
import app.scanova.feature.scan.render.LiveState
import app.scanova.feature.scan.render.PageLook
import app.scanova.feature.scan.render.PreviewMaker
import app.scanova.feature.scan.render.RecipeGeometry
import app.scanova.feature.scan.session.CameraMode
import app.scanova.feature.scan.session.DraftPage
import app.scanova.feature.scan.session.PageProcessor
import app.scanova.feature.scan.session.ScanSession
import app.scanova.feature.scan.ui.LookMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ReviewTab { FILTERS, PAGE }

internal enum class ReviewProblem { SAVE_FAILED, PAGES_FAILED, FLATTEN_FAILED, CLEANUP_FAILED }

internal data class ReviewPage(
    val id: String,
    val mode: CameraMode,
    /** The rendered page's thumbnail, or the quick one from the camera while it is processed. */
    val thumbnail: Bitmap?,
    /** The finished page's thumbnail: previews decoded for it are current ([PagePreview.version]). */
    val version: Bitmap?,
    val look: PageLook,
    /** The recipe as the scan renders it (from the upright working copy). */
    val edit: PageEdit,
    /** Width / height of the page as it will look. */
    val aspect: Float,
    val processing: Boolean,
    val failed: Boolean,
    /** No page edges were found in the photo and the user has not dealt with the outline yet ([EdgeHint]). */
    val edgesNotFound: Boolean = false,
    /** A document page, which the "Flatten" tool applies to (not an ID card). */
    val canFlatten: Boolean = false,
    /** Flattened along the curl model's mesh, automatically or by choice. */
    val flattened: Boolean = false,
    /** Finished with the stand-in look because its Server clean-up failed. */
    val cleanupMissing: Boolean = false,
)

/**
 * When the review says "no page edges found" on a page: a document page
 * (not an ID card) whose photo showed no page edges, until the user has
 * opened its crop or its outline has changed. Afterwards the page is theirs.
 */
internal object EdgeHint {
    fun shows(mode: CameraMode, detectedOutline: Quad?, outline: Quad?, firstOutline: Quad?, cropOpened: Boolean): Boolean =
        mode == CameraMode.DOCUMENT && detectedOutline == null && !cropOpened && outline == firstOutline
}

/** A page at preview size, decoded for finished thumbnail [version]. */
internal class PagePreview(val version: Bitmap, val image: Bitmap)

internal data class ReviewUiState(
    val pages: List<ReviewPage> = emptyList(),
    /** The page shown large; its tools and look apply to it. */
    val selectedPageId: String? = null,
    /** Large images of the selected page and its neighbours, by page id. */
    val previews: Map<String, PagePreview> = emptyMap(),
    /** Live layers and filter previews of the selected page. */
    val live: LiveState = LiveState(),
    val tab: ReviewTab = ReviewTab.FILTERS,
    val lookMode: LookMode = LookMode.FILTERS,
    /** This account can clean pages on its server: the filter row offers Server clean-up. */
    val cleanupAvailable: Boolean = false,
    val title: String = "",
    val editingTitle: Boolean = false,
    val titleMissing: Boolean = false,
    val isSaving: Boolean = false,
    /** 0..1 while the PDF is being written. */
    val saveProgress: Float = 0f,
    val problem: ReviewProblem? = null,
    val confirmingDiscard: Boolean = false,
    /** Set while "delete this page?" is asked. */
    val confirmingDeleteId: String? = null,
    /** Set when the document was saved: the screen leaves the scan flow. */
    val savedDocumentId: String? = null,
    /** Set when the scan was discarded: the screen leaves the scan flow. */
    val discarded: Boolean = false,
) {
    val selectedIndex: Int
        get() = pages.indexOfFirst { it.id == selectedPageId }.coerceAtLeast(0)

    val selected: ReviewPage?
        get() = pages.getOrNull(selectedIndex)
}

/** State holder for the review of a new scan: pages, their look and order, and saving. */
@HiltViewModel
internal class ReviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ScanSession,
    private val processor: PageProcessor,
    private val scanRepository: ScanRepository,
    previewMaker: PreviewMaker,
    cleaner: PageCleaner,
    private val cleanedPages: CleanedPages,
    private val dispatchers: AppDispatchers,
    clock: TimeSource,
) : ViewModel() {

    private val capturedAt = clock.now()
    private var titleEditedByUser = false

    /** Page images being decoded for preview, by the version they belong to. */
    private val loadingPreviews = mutableSetOf<Bitmap>()

    /** Each page's outline when this screen first saw it, for [EdgeHint] (main thread). */
    private val firstOutlines = HashMap<String, Quad?>()

    /** Pages whose crop the user opened from here (main thread). */
    private val cropOpened = HashSet<String>()

    /** Pages the user tapped "Flatten" on whose answer from the model is still awaited (main thread). */
    private val flattenRequested = HashSet<String>()

    private val live = LivePreviewController(viewModelScope, dispatchers, previewMaker, cleanedPages) { pageId ->
        when (val loaded = scanRepository.loadCapture(pageId)) {
            is AppResult.Success -> withContext(dispatchers.default) {
                Bitmaps.decodeSampled(loaded.value, PreviewMaker.SOURCE_LONG_EDGE)
            }
            is AppResult.Failure -> null
        }
    }

    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(session.pages, processor.pending, processor.failed) { drafts, pending, failed ->
                Triple(drafts, pending, failed)
            }.collect { (drafts, pending, failed) -> onPagesChanged(drafts, pending, failed) }
        }
        viewModelScope.launch {
            live.state.collect { liveState -> mutableState.update { it.copy(live = liveState) } }
        }
        viewModelScope.launch {
            cleaner.available.collect { available -> mutableState.update { it.copy(cleanupAvailable = available) } }
        }
        // Anything not rendered yet (a failure earlier, say) gets another go.
        session.pages.value.filterNot { it.isUpToDate }.forEach { processor.enqueue(it.id) }
    }

    private fun onPagesChanged(drafts: List<DraftPage>, pending: Set<String>, failed: Set<String>) {
        drafts.forEach { if (!firstOutlines.containsKey(it.id)) firstOutlines[it.id] = it.outline }
        // A "Flatten" tap is answered once the model has looked: say so when it found nothing to flatten along.
        var flattenFailed = false
        flattenRequested.retainAll { pageId ->
            val page = drafts.firstOrNull { it.id == pageId } ?: return@retainAll false
            if (page.needsFlattening || page.flatten != FlattenChoice.ON) return@retainAll page.needsFlattening
            if (!page.isFlattened) flattenFailed = true
            false
        }
        mutableState.update { current ->
            // Not "out of date": the look changes with every slider move, and the live view already shows it.
            val pages = drafts.map { it.toReviewPage(processing = it.id in pending || it.finished == null, failed = it.id in failed) }
            // A page newly finished without its clean-up says so once (not again for the same image).
            val cleanupFailed = pages.any { page ->
                page.cleanupMissing && current.pages.none { it.id == page.id && it.cleanupMissing && it.version === page.version }
            }
            val ids = pages.map { it.id }.toSet()
            val known = current.pages.map { it.id }.toSet()
            val selected = when {
                pages.isEmpty() -> null
                // Arriving from the camera: start at the first page.
                current.pages.isEmpty() -> pages.first().id
                // A new or retaken page is the one the user wants to look at.
                pages.any { it.id !in known } -> pages.last { it.id !in known }.id
                current.selectedPageId in ids -> current.selectedPageId
                // The selected page was deleted: show the one that took its place.
                else -> pages.getOrNull(current.selectedIndex.coerceAtMost(pages.lastIndex))?.id
            }
            current.copy(
                pages = pages,
                selectedPageId = selected,
                previews = current.previews.filterKeys { it in ids },
                // Until the user types a title, keep proposing one from the first page.
                title = if (titleEditedByUser) current.title else suggestedTitle(drafts),
                problem = when {
                    flattenFailed -> ReviewProblem.FLATTEN_FAILED
                    cleanupFailed -> ReviewProblem.CLEANUP_FAILED
                    else -> current.problem.takeUnless {
                        (it == ReviewProblem.PAGES_FAILED && failed.isEmpty()) ||
                            (it == ReviewProblem.CLEANUP_FAILED && pages.none { page -> page.cleanupMissing })
                    }
                },
            )
        }
        showLive()
    }

    private fun showLive() {
        val selected = mutableState.value.selected ?: return
        live.show(selected.id, selected.edit)
    }

    // ── Title ───────────────────────────────────────────────────────────

    fun onEditTitle() {
        mutableState.update { it.copy(editingTitle = true) }
    }

    fun onTitleConfirmed(value: String) {
        val title = value.trim()
        if (title.isEmpty()) {
            mutableState.update { it.copy(titleMissing = true) }
            return
        }
        titleEditedByUser = true
        mutableState.update { it.copy(title = title, titleMissing = false, editingTitle = false) }
    }

    fun onTitleDismissed() {
        mutableState.update { it.copy(editingTitle = false, titleMissing = false) }
    }

    // ── Pages ───────────────────────────────────────────────────────────

    fun onPageSelected(pageId: String) {
        mutableState.update { if (it.pages.any { page -> page.id == pageId }) it.copy(selectedPageId = pageId) else it }
        showLive()
    }

    fun onTabSelected(tab: ReviewTab) {
        mutableState.update { it.copy(tab = tab) }
    }

    fun onLookModeChange(mode: LookMode) {
        mutableState.update { it.copy(lookMode = mode) }
    }

    /** The large view of [pageId] is about to be shown: decode it at preview size unless already done. */
    fun onPreviewNeeded(pageId: String) {
        val page = mutableState.value.pages.firstOrNull { it.id == pageId } ?: return
        val version = page.version ?: return
        if (mutableState.value.previews[pageId]?.version === version || version in loadingPreviews) return
        loadingPreviews += version
        viewModelScope.launch {
            val image = when (val loaded = scanRepository.loadPage(pageId)) {
                is AppResult.Success -> withContext(dispatchers.default) {
                    Bitmaps.decodeSampled(loaded.value, PreviewMaker.BASE_LONG_EDGE)
                }
                // The thumbnail stays in place: still a usable, if softer, view.
                is AppResult.Failure -> null
            }
            loadingPreviews -= version
            if (image != null) storePreview(pageId, PagePreview(version, image))
        }
    }

    fun onMovePage(pageId: String, offset: Int) = session.move(pageId, offset)

    /** The crop of [pageId] is being opened: its "no page edges found" note has done its job. */
    fun onCropOpened(pageId: String) {
        cropOpened += pageId
        mutableState.update { current ->
            current.copy(pages = current.pages.map { if (it.id == pageId) it.copy(edgesNotFound = false) else it })
        }
    }

    fun onRotate(turns: Int) {
        val pageId = editablePageId() ?: return
        session.update(pageId) { it.copy(rotation = RecipeGeometry.turn(it.rotation, turns)) }
        processor.enqueue(pageId)
    }

    /**
     * "Flatten": a flattened page goes back to its outline, any other page is
     * flattened along the curl model's mesh. A page the model found no mesh for
     * (it failed or was out of memory) is asked again, once per tap.
     */
    fun onFlattenToggled() {
        val pageId = editablePageId() ?: return
        var turningOn = false
        session.update(pageId) { page ->
            when {
                !page.canFlatten -> page
                page.isFlattened -> page.copy(flatten = FlattenChoice.OFF)
                else -> {
                    turningOn = true
                    page.copy(flatten = FlattenChoice.ON, flattening = page.flattening?.takeIf { it.mesh != null })
                }
            }
        }
        if (turningOn) {
            flattenRequested += pageId
            mutableState.update { it.copy(problem = it.problem.takeUnless { problem -> problem == ReviewProblem.FLATTEN_FAILED }) }
        }
        processor.enqueue(pageId)
    }

    fun onDeleteRequested(pageId: String) {
        mutableState.update { it.copy(confirmingDeleteId = pageId) }
    }

    fun onDeleteDismissed() {
        mutableState.update { it.copy(confirmingDeleteId = null) }
    }

    fun onDeleteConfirmed() {
        val pageId = mutableState.value.confirmingDeleteId ?: return
        mutableState.update { it.copy(confirmingDeleteId = null) }
        session.remove(pageId)
        live.forget(pageId)
        cleanedPages.forget(pageId)
        viewModelScope.launch { scanRepository.discardPage(pageId) }
    }

    /** The next photo taken replaces this page. */
    fun onRetakePage(pageId: String) {
        session.replaceTargetId = pageId
    }

    // ── Look ────────────────────────────────────────────────────────────

    /** A filter was chosen. Choosing Server clean-up (again) also retries a clean-up that failed. */
    fun onFilterSelected(filterId: String) {
        val pageId = editablePageId() ?: return
        session.update(pageId) { it.copy(look = it.look.copy(filterId = filterId)) }
        processor.enqueue(pageId, retryCleanup = retriesCleanup(filterId))
    }

    /** A slider moved: shown live, rendered when the finger lifts ([onLookChangeFinished]). */
    fun onLookChange(look: PageLook) {
        val pageId = editablePageId() ?: return
        session.update(pageId) { it.copy(look = look) }
    }

    fun onLookChangeFinished() {
        val pageId = editablePageId() ?: return
        processor.enqueue(pageId)
    }

    fun onApplyLookToAll() {
        val pageId = editablePageId() ?: return
        val look = session.page(pageId)?.look ?: return
        session.updateAll { it.copy(look = look) }
        val retry = retriesCleanup(look.filterId)
        session.pages.value.forEach { processor.enqueue(it.id, retryCleanup = retry) }
    }

    /** Server clean-up chosen: failed clean-ups are tried again, so their note goes until they fail again. */
    private fun retriesCleanup(filterId: String): Boolean {
        if (!ServerCleanup.isCleanup(filterId)) return false
        mutableState.update { it.copy(problem = it.problem.takeUnless { problem -> problem == ReviewProblem.CLEANUP_FAILED }) }
        return true
    }

    private fun editablePageId(): String? = mutableState.value.takeUnless { it.isSaving }?.selected?.id

    // ── Leaving ─────────────────────────────────────────────────────────

    fun onDiscardRequested() {
        // Nothing scanned, nothing to confirm.
        if (session.pages.value.isEmpty()) onDiscardConfirmed() else mutableState.update { it.copy(confirmingDiscard = true) }
    }

    fun onDiscardDismissed() {
        mutableState.update { it.copy(confirmingDiscard = false) }
    }

    fun onDiscardConfirmed() {
        val pageIds = session.clear()
        pageIds.forEach(cleanedPages::forget)
        mutableState.update { it.copy(confirmingDiscard = false, discarded = true) }
        viewModelScope.launch { pageIds.forEach { scanRepository.discardPage(it) } }
    }

    fun onSave() {
        val current = mutableState.value
        if (current.isSaving || current.pages.isEmpty()) return
        val title = current.title.trim()
        if (title.isEmpty()) {
            mutableState.update { it.copy(titleMissing = true, editingTitle = true) }
            return
        }
        mutableState.update { it.copy(isSaving = true, saveProgress = 0f, problem = null) }
        viewModelScope.launch {
            // Pages still being rendered are finished first.
            processor.awaitIdle()
            val drafts = session.pages.value
            if (drafts.isEmpty() || drafts.any { !it.isUpToDate }) {
                mutableState.update { it.copy(isSaving = false, problem = ReviewProblem.PAGES_FAILED) }
                return@launch
            }
            val document = ScannedDocument(
                title = title,
                capturedAt = capturedAt,
                pages = drafts.map { draft ->
                    val finished = checkNotNull(draft.finished)
                    // The recipe for the original: crop in fractions of the upright photo, plus the camera's rotation.
                    ScannedPage(draft.id, finished.width, finished.height, finished.ocrText, edit = draft.toEdit())
                },
            )
            // One step per page, plus one for finishing the file.
            val steps = drafts.size + 1f
            val result = scanRepository.saveDocument(document) { output, readPage ->
                // Pages are decrypted and embedded one at a time, straight into the encrypted PDF.
                val writer = PdfWriter(output, capturedAt, title)
                drafts.forEachIndexed { index, page ->
                    writer.addJpegPage(readPage(page.id))
                    mutableState.update { it.copy(saveProgress = (index + 1) / steps) }
                }
                writer.finish()
            }
            when (result) {
                is AppResult.Success -> {
                    session.clear()
                    mutableState.update { it.copy(isSaving = false, saveProgress = 1f, savedDocumentId = result.value) }
                }
                is AppResult.Failure -> mutableState.update {
                    it.copy(isSaving = false, saveProgress = 0f, problem = ReviewProblem.SAVE_FAILED)
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Keeps [preview] if it is still current, and drops previews far from the selected page. */
    private fun storePreview(pageId: String, preview: PagePreview) {
        mutableState.update { current ->
            val page = current.pages.firstOrNull { it.id == pageId }
            if (page == null || page.version !== preview.version) return@update current
            val selected = current.selectedIndex
            val kept = current.previews.filterKeys { id ->
                val index = current.pages.indexOfFirst { it.id == id }
                index >= 0 && abs(index - selected) <= PREVIEW_NEIGHBOURS
            }
            current.copy(previews = kept + (pageId to preview))
        }
    }

    private fun suggestedTitle(pages: List<DraftPage>): String {
        val fromText = pages.firstOrNull()?.finished?.ocrText?.let(TitleSuggester::suggest)
        if (fromText != null) return fromText
        val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(capturedAt.atZone(ZoneId.systemDefault()))
        return context.getString(R.string.feature_scan_default_title, formatted)
    }

    private fun DraftPage.toReviewPage(processing: Boolean, failed: Boolean): ReviewPage {
        val edit = toWorkingEdit()
        val image = finished?.thumbnail ?: quickThumbnail
        val aspect = when {
            // A page turned since its last render shows turned in a moment: use the new shape at once.
            finished != null && finished.recipe.rotation == edit.rotation && finished.recipe.crop == edit.crop &&
                finished.recipe.flatten == edit.flatten ->
                finished.width.toFloat() / finished.height.coerceAtLeast(1)
            image != null -> {
                val sideways = RecipeGeometry.quarterTurns(edit.rotation - (finished?.recipe?.rotation ?: 0)) % 2 == 1
                if (sideways) image.height.toFloat() / image.width.coerceAtLeast(1) else image.width.toFloat() / image.height.coerceAtLeast(1)
            }
            else -> captureWidth.toFloat() / captureHeight.coerceAtLeast(1)
        }
        return ReviewPage(
            id = id,
            mode = mode,
            thumbnail = image,
            version = finished?.thumbnail,
            look = look,
            edit = edit,
            aspect = aspect,
            processing = processing,
            failed = failed,
            edgesNotFound = EdgeHint.shows(mode, detectedOutline, outline, firstOutlines[id], cropOpened = id in cropOpened),
            // Not offered where the model cannot run (OpenCV or the model failed to load).
            canFlatten = canFlatten && PageFlattener.canRun,
            flattened = isFlattened,
            cleanupMissing = cleanupMissing,
        )
    }

    private companion object {
        /** Previews kept on each side of the selected page, for smooth swiping. */
        const val PREVIEW_NEIGHBOURS = 1
    }
}
