package app.scanova.feature.documents.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.backup.BackupRepository
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.model.Document
import app.scanova.core.data.settings.LibraryLayout
import app.scanova.core.data.settings.SettingsRepository
import app.scanova.feature.documents.common.DocumentThumbnails
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Something the Files tab reports once, in a snackbar. */
internal sealed interface FilesMessage {
    data class RefreshFailed(val error: AppError) : FilesMessage

    /** [count] of the picked documents could not be deleted. */
    data class DeleteFailed(val count: Int) : FilesMessage
}

internal data class FilesUiState(
    val isLoading: Boolean = true,
    /** The local database could not be read. */
    val loadFailed: Boolean = false,
    val query: String = "",
    /** What is listed: every document, or the matches of [query], in [sort] order. */
    val documents: List<Document> = emptyList(),
    val sort: DocumentSort = DocumentSort.NEWEST,
    val layout: LibraryLayout = LibraryLayout.LIST,
    /** Picked documents; the selection is open while this is not empty. */
    val selectedIds: Set<String> = emptySet(),
    /** An account is signed in, so documents have a backup state and refreshing means something. */
    val signedIn: Boolean = false,
    val isRefreshing: Boolean = false,
    val confirmingDelete: Boolean = false,
    val isDeleting: Boolean = false,
    val message: FilesMessage? = null,
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()

    val allSelected: Boolean get() = documents.isNotEmpty() && documents.all { it.id in selectedIds }

    /** Nothing scanned yet (as opposed to a search with no results). */
    val isEmptyLibrary: Boolean get() = !isLoading && !loadFailed && documents.isEmpty() && query.isBlank()

    val selectedDocuments: List<Document> get() = documents.filter { it.id in selectedIds }
}

/** State holder for the Files tab: every document, sorted, searchable, selectable. */
@HiltViewModel
internal class FilesViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository,
    private val thumbnails: DocumentThumbnails,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(
        savedStateHandle.get<String>(KEY_SORT)?.let { name -> DocumentSort.entries.firstOrNull { it.name == name } }
            ?: DocumentSort.NEWEST,
    )
    private val mutableState = MutableStateFlow(FilesUiState(sort = sort.value))
    val state: StateFlow<FilesUiState> = mutableState.asStateFlow()

    private var documentsJob: Job? = null

    init {
        observeDocuments()
        viewModelScope.launch {
            settingsRepository.settings
                .catch { error -> ScanovaLog.warn(TAG, "settings_failed", error) }
                .collect { settings -> mutableState.update { it.copy(layout = settings.libraryLayout) } }
        }
        viewModelScope.launch {
            backupRepository.status
                .map { it.enabled }
                .distinctUntilChanged()
                .catch { emit(false) }
                .collect { signedIn -> mutableState.update { it.copy(signedIn = signedIn) } }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeDocuments() {
        documentsJob?.cancel()
        documentsJob = viewModelScope.launch {
            val matches = query
                // Wait for a pause in typing before searching.
                .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MILLIS }
                .map { it.trim() }
                .distinctUntilChanged()
                .flatMapLatest { documentRepository.observeDocuments(it) }
            combine(matches, sort) { documents, order -> sortDocuments(documents, order) }
                .catch { error ->
                    ScanovaLog.error(TAG, "files_load_failed", error)
                    mutableState.update { it.copy(isLoading = false, loadFailed = true) }
                }
                .collect { documents ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            loadFailed = false,
                            documents = documents,
                            selectedIds = pruneSelection(it.selectedIds, documents),
                        )
                    }
                }
        }
    }

    suspend fun cover(document: Document) = thumbnails.cover(document)

    fun onQueryChange(value: String) {
        query.value = value
        mutableState.update { it.copy(query = value) }
    }

    fun onSortChange(value: DocumentSort) {
        savedStateHandle[KEY_SORT] = value.name
        sort.value = value
        mutableState.update { it.copy(sort = value) }
    }

    fun onToggleLayout() {
        val next = if (mutableState.value.layout == LibraryLayout.LIST) LibraryLayout.GRID else LibraryLayout.LIST
        mutableState.update { it.copy(layout = next) }
        viewModelScope.launch {
            runCatching { settingsRepository.setLibraryLayout(next) }
                .onFailure { error -> ScanovaLog.warn(TAG, "layout_save_failed", error) }
        }
    }

    // ── Selection ───────────────────────────────────────────────────────

    /** Long press: opens the selection with this document, or toggles it when one is open. */
    fun onToggleSelection(documentId: String) {
        mutableState.update { it.copy(selectedIds = toggleSelection(it.selectedIds, documentId)) }
    }

    fun onToggleSelectAll() {
        mutableState.update { it.copy(selectedIds = toggleSelectAll(it.selectedIds, it.documents)) }
    }

    fun onClearSelection() {
        mutableState.update { it.copy(selectedIds = emptySet(), confirmingDelete = false) }
    }

    /** The picked documents in list order, and closes the selection (the share sheet takes over). */
    fun takeSelectionForSharing(): List<String> {
        val current = mutableState.value
        val ids = selectedInDisplayOrder(current.selectedIds, current.documents)
        onClearSelection()
        return ids
    }

    fun onDeleteRequested() {
        if (mutableState.value.selectionMode) mutableState.update { it.copy(confirmingDelete = true) }
    }

    fun onDeleteDismissed() {
        mutableState.update { it.copy(confirmingDelete = false) }
    }

    fun onDeleteConfirmed() {
        val ids = selectedInDisplayOrder(mutableState.value.selectedIds, mutableState.value.documents)
        if (ids.isEmpty()) return onDeleteDismissed()
        mutableState.update { it.copy(confirmingDelete = false, isDeleting = true) }
        viewModelScope.launch {
            val failed = ids.count { id -> documentRepository.delete(id) is AppResult.Failure }
            mutableState.update {
                it.copy(
                    isDeleting = false,
                    selectedIds = emptySet(),
                    message = if (failed > 0) FilesMessage.DeleteFailed(failed) else it.message,
                )
            }
        }
    }

    // ── Refresh and errors ──────────────────────────────────────────────

    /** Pull-to-refresh. Only offered while signed in; without an account there is nothing to fetch. */
    fun onRefresh() {
        val current = mutableState.value
        if (!current.signedIn || current.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val result = documentRepository.refresh()
            mutableState.update {
                it.copy(
                    isRefreshing = false,
                    message = (result as? AppResult.Failure)?.let { failure -> FilesMessage.RefreshFailed(failure.error) }
                        ?: it.message,
                )
            }
        }
    }

    fun onMessageShown() {
        mutableState.update { it.copy(message = null) }
    }

    fun onRetryLoad() {
        mutableState.update { it.copy(isLoading = true, loadFailed = false) }
        observeDocuments()
    }

    private companion object {
        const val TAG = "Files"
        const val KEY_SORT = "files_sort"
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
