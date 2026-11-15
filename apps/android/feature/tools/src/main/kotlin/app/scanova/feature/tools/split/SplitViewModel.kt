package app.scanova.feature.tools.split

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.documents.PageDraft
import app.scanova.core.data.model.Document
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.CoverLoader
import app.scanova.feature.tools.common.CreatedDocument
import app.scanova.feature.tools.common.DocumentBuilder
import app.scanova.feature.tools.common.DocumentList
import app.scanova.feature.tools.common.PageThumbnails
import app.scanova.feature.tools.common.ToolError
import app.scanova.feature.tools.common.WorkProgress
import app.scanova.feature.tools.common.toToolError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class SplitStep { PICK, PAGES }

internal sealed interface PagesState {
    data object Loading : PagesState

    data class Failed(val error: ToolError) : PagesState

    data class Ready(val count: Int) : PagesState
}

internal data class SplitUiState(
    val step: SplitStep = SplitStep.PICK,
    val document: Document? = null,
    val pages: PagesState = PagesState.Loading,
    val mode: SplitMode = SplitMode.SELECTED,
    /** 0-based pages chosen in [SplitMode.SELECTED]. */
    val selected: Set<Int> = emptySet(),
    val ranges: String = "",
    val working: WorkProgress? = null,
    val error: ToolError? = null,
    /** Several documents were made: listed before leaving. */
    val created: List<CreatedDocument>? = null,
    /** Exactly one document was made: the screen opens it. */
    val openId: String? = null,
) {
    val pageCount: Int get() = (pages as? PagesState.Ready)?.count ?: 0

    /** The documents a split would create now (0-based pages each). */
    val plan: List<List<Int>> get() = PageSelection.plan(mode, selected, ranges, pageCount)

    /** What is wrong with the typed ranges, once something has been typed. */
    val rangeError: RangeError?
        get() = if (mode == SplitMode.RANGES && ranges.isNotBlank()) {
            (PageSelection.parseRanges(ranges, pageCount) as? RangeParse.Invalid)?.error
        } else {
            null
        }
}

/**
 * Split: pick a document, choose pages (or every page, or typed ranges), and
 * each part becomes a new document with copies of those pages. The document
 * that was split stays exactly as it was.
 */
@HiltViewModel
internal class SplitViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    documents: DocumentRepository,
    private val builder: DocumentBuilder,
    val covers: CoverLoader,
) : ViewModel() {

    val list = DocumentList(documents, viewModelScope)

    private val mutableState = MutableStateFlow(SplitUiState())
    val state: StateFlow<SplitUiState> = mutableState.asStateFlow()

    private var thumbnails: PageThumbnails? = null
    private var loadJob: Job? = null
    private var job: Job? = null

    fun onPick(document: Document) {
        mutableState.update {
            it.copy(
                step = SplitStep.PAGES,
                document = document,
                pages = PagesState.Loading,
                selected = emptySet(),
                ranges = "",
                error = null,
            )
        }
        loadPages(document)
    }

    fun onRetryPages() {
        state.value.document?.let(::loadPages)
    }

    private fun loadPages(document: Document) {
        closeThumbnails()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val pages = try {
                val opened = builder.openThumbnails(document.id)
                thumbnails = opened
                PagesState.Ready(opened.count)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PagesState.Failed(e.toToolError())
            }
            mutableState.update { it.copy(pages = pages) }
        }
    }

    /** A small image of page [index], or null when it cannot be shown. */
    suspend fun thumbnail(index: Int): Bitmap? = thumbnails?.load(index)

    /** Back from the pages to the document list. Returns false when there is nowhere to go back to here. */
    fun onBack(): Boolean {
        val current = state.value
        if (current.working != null) {
            onStop()
            return true
        }
        if (current.step == SplitStep.PAGES && current.created == null) {
            loadJob?.cancel()
            closeThumbnails()
            mutableState.update { it.copy(step = SplitStep.PICK, document = null, pages = PagesState.Loading, error = null) }
            return true
        }
        return false
    }

    fun onMode(mode: SplitMode) = mutableState.update { if (it.working == null) it.copy(mode = mode, error = null) else it }

    fun onTogglePage(index: Int) = mutableState.update { current ->
        if (current.working != null || current.mode != SplitMode.SELECTED) return@update current
        current.copy(selected = if (index in current.selected) current.selected - index else current.selected + index, error = null)
    }

    fun onSelectAll() = mutableState.update { it.copy(selected = (0 until it.pageCount).toSet()) }

    fun onClearSelection() = mutableState.update { it.copy(selected = emptySet()) }

    fun onRangesChange(value: String) = mutableState.update { it.copy(ranges = value.take(MAX_RANGES_LENGTH), error = null) }

    fun onSplit() {
        val current = state.value
        val document = current.document ?: return
        val parts = current.plan
        if (current.working != null || parts.isEmpty()) return
        val total = parts.sumOf { it.size }
        mutableState.update { it.copy(working = WorkProgress(0, total), error = null) }
        job = viewModelScope.launch {
            var unused: List<PageDraft> = emptyList()
            try {
                val drafts = builder.copyPages(document.id, parts) { done, all ->
                    mutableState.update { it.copy(working = WorkProgress(done, all)) }
                }
                unused = drafts.flatten()
                val created = drafts.mapIndexed { index, pages ->
                    val title = partTitle(document.title, parts[index])
                    val id = builder.create(title, pages)
                    unused = drafts.drop(index + 1).flatten()
                    CreatedDocument(id, title, pages.size)
                }
                mutableState.update {
                    if (created.size == 1) {
                        it.copy(working = null, openId = created.single().id)
                    } else {
                        it.copy(working = null, created = created)
                    }
                }
            } catch (e: CancellationException) {
                builder.discard(unused)
                throw e
            } catch (e: Throwable) {
                builder.discard(unused)
                mutableState.update { it.copy(working = null, error = e.toToolError()) }
            }
        }
    }

    fun onStop() {
        job?.cancel()
        job = null
        mutableState.update { it.copy(working = null) }
    }

    fun onOpenHandled() = mutableState.update { it.copy(openId = null) }

    private fun partTitle(title: String, pages: List<Int>): String = context.getString(
        if (pages.size == 1) R.string.feature_tools_split_part_title_one else R.string.feature_tools_split_part_title_many,
        title,
        PageSelection.describe(pages),
    )

    private fun closeThumbnails() {
        val opened = thumbnails ?: return
        thumbnails = null
        runCatching { opened.close() }
    }

    override fun onCleared() {
        job?.cancel()
        loadJob?.cancel()
        closeThumbnails()
        covers.clear()
    }

    private companion object {
        const val MAX_RANGES_LENGTH = 200
    }
}
