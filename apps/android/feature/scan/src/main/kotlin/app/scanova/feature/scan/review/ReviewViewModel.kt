package app.scanova.feature.scan.review

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.scan.ScanRepository
import app.scanova.core.data.scan.ScannedDocument
import app.scanova.core.data.scan.ScannedPage
import app.scanova.feature.scan.R
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.ocr.TitleSuggester
import app.scanova.feature.scan.pdf.PdfWriter
import app.scanova.feature.scan.session.DraftPage
import app.scanova.feature.scan.session.PageFinisher
import app.scanova.feature.scan.session.ScanFilter
import app.scanova.feature.scan.session.ScanSession
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ReviewPage(
    val id: String,
    val thumbnail: Bitmap,
    val filter: ScanFilter,
)

/** A page at preview size. Valid only while [thumbnail] is still the page's thumbnail (same version). */
class PagePreview(val thumbnail: Bitmap, val image: Bitmap)

enum class ReviewProblem { SAVE_FAILED, LOOK_FAILED }

data class ReviewUiState(
    val pages: List<ReviewPage> = emptyList(),
    /** The page shown large; its tools and look apply to it. */
    val selectedPageId: String? = null,
    /** Large images of the selected page and its neighbours, by page id. */
    val previews: Map<String, PagePreview> = emptyMap(),
    /** Pages being given a new look, with the look they are getting. */
    val changingLook: Map<String, ScanFilter> = emptyMap(),
    val title: String = "",
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

    /** The look shown as chosen for [page]: the one it is getting, or the one it has. */
    fun lookOf(page: ReviewPage): ScanFilter = changingLook[page.id] ?: page.filter
}

/** State holder for the multi-page review screen. */
@HiltViewModel
internal class ReviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ScanSession,
    private val scanRepository: ScanRepository,
    private val pageFinisher: PageFinisher,
    private val dispatchers: AppDispatchers,
    clock: TimeSource,
) : ViewModel() {

    private val capturedAt = clock.now()
    private var titleEditedByUser = false

    /** Page images being decoded for preview, by the thumbnail (version) they belong to. */
    private val loadingPreviews = mutableSetOf<Bitmap>()

    /** One re-render at a time: two renders of the same page must not write its file together. */
    private val renderLock = Mutex()

    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            session.pages.collect { drafts ->
                val finished = drafts.filter { it.finished != null }
                mutableState.update { current ->
                    val pages = finished.map { ReviewPage(it.id, it.finished!!.thumbnail, it.filter) }
                    val ids = pages.map { it.id }.toSet()
                    val known = current.pages.map { it.id }.toSet()
                    val selected = when {
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
                        title = if (titleEditedByUser) current.title else suggestedTitle(finished),
                    )
                }
            }
        }
    }

    fun onTitleChange(value: String) {
        titleEditedByUser = true
        mutableState.update { it.copy(title = value, titleMissing = false) }
    }

    fun onPageSelected(pageId: String) {
        mutableState.update { if (it.pages.any { page -> page.id == pageId }) it.copy(selectedPageId = pageId) else it }
    }

    /** The large view of [pageId] is about to be shown: decode it at preview size unless already done. */
    fun onPreviewNeeded(pageId: String) {
        val page = mutableState.value.pages.firstOrNull { it.id == pageId } ?: return
        val version = page.thumbnail
        if (mutableState.value.previews[pageId]?.thumbnail === version || version in loadingPreviews) return
        loadingPreviews += version
        viewModelScope.launch {
            val image = when (val loaded = scanRepository.loadPage(pageId)) {
                is AppResult.Success -> withContext(dispatchers.default) {
                    Bitmaps.decodeSampled(loaded.value, PageFinisher.PREVIEW_LONG_EDGE)
                }
                // The thumbnail stays in place: still a usable, if softer, view.
                is AppResult.Failure -> null
            }
            loadingPreviews -= version
            if (image != null) storePreview(pageId, PagePreview(version, image))
        }
    }

    fun onMovePage(pageId: String, offset: Int) = session.move(pageId, offset)

    fun onDeleteRequested(pageId: String) {
        mutableState.update { it.copy(confirmingDeleteId = pageId) }
    }

    fun onDeleteDismissed() {
        mutableState.update { it.copy(confirmingDeleteId = null) }
    }

    fun onDeleteConfirmed() {
        val pageId = mutableState.value.confirmingDeleteId ?: return
        mutableState.update { it.copy(confirmingDeleteId = null) }
        if (pageId in mutableState.value.changingLook) return
        session.remove(pageId)
        viewModelScope.launch { scanRepository.discardPage(pageId) }
    }

    /** The next photo taken replaces this page. */
    fun onRetakePage(pageId: String) {
        session.replaceTargetId = pageId
    }

    /**
     * Gives page [pageId] a new look. The page is rendered again from its
     * capture with the same outline; the text read from it is kept.
     */
    fun onLookSelected(pageId: String, filter: ScanFilter) {
        val current = mutableState.value
        val page = current.pages.firstOrNull { it.id == pageId } ?: return
        if (current.isSaving || current.lookOf(page) == filter) return
        mutableState.update {
            it.copy(
                changingLook = it.changingLook + (pageId to filter),
                problem = it.problem.takeUnless { problem -> problem == ReviewProblem.LOOK_FAILED },
            )
        }
        viewModelScope.launch {
            renderLock.withLock {
                // A later choice for the same page replaces this one.
                if (mutableState.value.changingLook[pageId] != filter) return@withLock
                val output = renderLook(pageId, filter)
                if (output != null && session.page(pageId) != null) {
                    session.update(pageId) { it.copy(filter = filter, finished = output.page) }
                    storePreview(pageId, PagePreview(output.page.thumbnail, output.preview))
                }
                mutableState.update {
                    val latest = it.changingLook[pageId] == filter
                    it.copy(
                        changingLook = if (latest) it.changingLook - pageId else it.changingLook,
                        problem = if (output == null && latest) ReviewProblem.LOOK_FAILED else it.problem,
                    )
                }
            }
        }
    }

    fun onDiscardRequested() {
        // Nothing scanned, nothing to confirm.
        if (session.pages.value.isEmpty()) onDiscardConfirmed() else mutableState.update { it.copy(confirmingDiscard = true) }
    }

    fun onDiscardDismissed() {
        mutableState.update { it.copy(confirmingDiscard = false) }
    }

    fun onDiscardConfirmed() {
        val pageIds = session.clear()
        mutableState.update { it.copy(confirmingDiscard = false, discarded = true) }
        viewModelScope.launch { pageIds.forEach { scanRepository.discardPage(it) } }
    }

    fun onSave() {
        val current = mutableState.value
        if (current.isSaving || current.changingLook.isNotEmpty()) return
        val title = current.title.trim()
        if (title.isEmpty()) {
            mutableState.update { it.copy(titleMissing = true) }
            return
        }
        val drafts = session.pages.value
        val pages = drafts.filter { it.finished != null }
        if (pages.isEmpty()) return

        mutableState.update { it.copy(isSaving = true, saveProgress = 0f, problem = null) }
        viewModelScope.launch {
            val document = ScannedDocument(
                title = title,
                capturedAt = capturedAt,
                pages = pages.map { ScannedPage(it.id, it.finished!!.width, it.finished.height, it.finished.ocrText) },
            )
            // One step per page, plus one for finishing the file.
            val steps = pages.size + 1f
            val result = scanRepository.saveDocument(document) { output, readPage ->
                // Pages are decrypted and embedded one at a time, straight into the encrypted PDF.
                val writer = PdfWriter(output, capturedAt, title)
                pages.forEachIndexed { index, page ->
                    writer.addJpegPage(readPage(page.id))
                    mutableState.update { it.copy(saveProgress = (index + 1) / steps) }
                }
                writer.finish()
            }
            when (result) {
                is AppResult.Success -> {
                    session.clear()
                    // Captures that never became pages are no longer needed.
                    drafts.filter { it.finished == null }.forEach { scanRepository.discardPage(it.id) }
                    mutableState.update { it.copy(isSaving = false, saveProgress = 1f, savedDocumentId = result.value) }
                }
                is AppResult.Failure -> mutableState.update {
                    it.copy(isSaving = false, saveProgress = 0f, problem = ReviewProblem.SAVE_FAILED)
                }
            }
        }
    }

    private suspend fun renderLook(pageId: String, filter: ScanFilter): PageFinisher.Output? {
        val draft = session.page(pageId) ?: return null
        val finished = draft.finished ?: return null
        val capture = when (val loaded = scanRepository.loadCapture(pageId)) {
            is AppResult.Success -> loaded.value
            is AppResult.Failure -> return null
        }
        return withContext(dispatchers.default) {
            pageFinisher.finish(pageId, capture, draft.outline, filter, knownText = finished.ocrText)
        }
    }

    /** Keeps [preview] if it is still current, and drops previews far from the selected page. */
    private fun storePreview(pageId: String, preview: PagePreview) {
        mutableState.update { current ->
            val page = current.pages.firstOrNull { it.id == pageId }
            if (page == null || page.thumbnail !== preview.thumbnail) return@update current
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

    private companion object {
        /** Previews kept on each side of the selected page, for smooth swiping. */
        const val PREVIEW_NEIGHBOURS = 1
    }
}
