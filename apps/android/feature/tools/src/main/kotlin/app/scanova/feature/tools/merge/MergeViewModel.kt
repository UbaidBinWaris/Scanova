package app.scanova.feature.tools.merge

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.documents.PageDraft
import app.scanova.core.data.model.Document
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.CoverLoader
import app.scanova.feature.tools.common.DocumentBuilder
import app.scanova.feature.tools.common.DocumentList
import app.scanova.feature.tools.common.ToolError
import app.scanova.feature.tools.common.WorkProgress
import app.scanova.feature.tools.common.formatDate
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

internal enum class MergeStep { PICK, ARRANGE }

internal data class MergeUiState(
    val step: MergeStep = MergeStep.PICK,
    /** Picked documents in merge order. */
    val selection: List<Document> = emptyList(),
    val title: String = "",
    /** Pages are being copied into the new document. */
    val working: WorkProgress? = null,
    val error: ToolError? = null,
    /** The merged document exists: the screen opens it. */
    val mergedId: String? = null,
) {
    val canContinue: Boolean get() = selection.size >= MIN_DOCUMENTS

    companion object {
        const val MIN_DOCUMENTS = 2
    }
}

/**
 * Merge: pick two or more documents (the order picked is the merge order),
 * rearrange them, name the result. The pages are copied, originals and
 * recipes included, so every page stays editable; the picked documents are
 * left as they are.
 */
@HiltViewModel
internal class MergeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    documents: DocumentRepository,
    private val builder: DocumentBuilder,
    private val clock: TimeSource,
    val covers: CoverLoader,
) : ViewModel() {

    val list = DocumentList(documents, viewModelScope)

    private val mutableState = MutableStateFlow(MergeUiState())
    val state: StateFlow<MergeUiState> = mutableState.asStateFlow()

    private var job: Job? = null

    fun onToggle(document: Document) = mutableState.update { current ->
        val picked = current.selection.any { it.id == document.id }
        current.copy(
            selection = if (picked) current.selection.filterNot { it.id == document.id } else current.selection + document,
            error = null,
        )
    }

    fun onContinue() = mutableState.update { current ->
        if (!current.canContinue) return@update current
        val title = current.title.ifBlank { context.getString(R.string.feature_tools_merge_default_title, formatDate(clock.now())) }
        current.copy(step = MergeStep.ARRANGE, title = title, error = null)
    }

    /** Back from arranging to picking. Returns false when there is nowhere to go back to here. */
    fun onBack(): Boolean {
        val current = state.value
        if (current.working != null) {
            onStop()
            return true
        }
        if (current.step == MergeStep.ARRANGE) {
            mutableState.update { it.copy(step = MergeStep.PICK, error = null) }
            return true
        }
        return false
    }

    fun onMove(from: Int, to: Int) = mutableState.update { current ->
        if (current.working != null || from !in current.selection.indices || to !in current.selection.indices) return@update current
        val reordered = current.selection.toMutableList().apply { add(to, removeAt(from)) }
        current.copy(selection = reordered)
    }

    fun onRemove(documentId: String) = mutableState.update { current ->
        if (current.working != null) return@update current
        val remaining = current.selection.filterNot { it.id == documentId }
        current.copy(
            selection = remaining,
            step = if (remaining.size < MergeUiState.MIN_DOCUMENTS) MergeStep.PICK else current.step,
        )
    }

    fun onTitleChange(value: String) = mutableState.update { it.copy(title = value.take(MAX_TITLE_LENGTH)) }

    fun onMerge() {
        val current = state.value
        if (current.working != null || !current.canContinue) return
        val documents = current.selection
        val title = current.title.trim().ifEmpty { context.getString(R.string.feature_tools_merge_default_title, formatDate(clock.now())) }
        val total = documents.sumOf { it.pageCount }
        mutableState.update { it.copy(working = WorkProgress(0, total), error = null) }
        job = viewModelScope.launch {
            val pages = mutableListOf<PageDraft>()
            try {
                for (document in documents) {
                    val before = pages.size
                    pages += builder.copyPages(document.id) { done, _ ->
                        mutableState.update { it.copy(working = WorkProgress(before + done, total)) }
                    }.flatten()
                }
                val id = builder.create(title, pages)
                mutableState.update { it.copy(working = null, mergedId = id) }
            } catch (e: CancellationException) {
                builder.discard(pages)
                throw e
            } catch (e: Throwable) {
                builder.discard(pages)
                mutableState.update { it.copy(working = null, error = e.toToolError()) }
            }
        }
    }

    fun onStop() {
        job?.cancel()
        job = null
        mutableState.update { it.copy(working = null) }
    }

    fun onMergedHandled() = mutableState.update { it.copy(mergedId = null) }

    override fun onCleared() {
        job?.cancel()
        covers.clear()
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 200
    }
}
