package app.scanova.feature.documents.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.data.backup.BackupRepository
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.model.Document
import app.scanova.core.data.model.SyncState
import app.scanova.feature.documents.common.BackupSummary
import app.scanova.feature.documents.common.ConnectivityObserver
import app.scanova.feature.documents.common.DocumentThumbnails
import app.scanova.feature.documents.common.backupSummary
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class HomeUiState(
    val isLoading: Boolean = true,
    /** The local database could not be read. */
    val loadFailed: Boolean = false,
    /** The newest few documents. */
    val recent: List<Document> = emptyList(),
    val documentCount: Int = 0,
    val query: String = "",
    val searchAnswer: SearchAnswer? = null,
    /** Null until known, so a signed-in user never sees "Back up" flash by. */
    val backup: BackupSummary? = null,
) {
    val signedIn: Boolean get() = backup != null && backup != BackupSummary.Off

    val search: SearchState get() = searchState(query, searchAnswer)

    val isEmptyLibrary: Boolean get() = !isLoading && !loadFailed && documentCount == 0
}

/** State holder for Home: recent documents, search, and the backup chip. */
@HiltViewModel
internal class HomeViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val backupRepository: BackupRepository,
    private val connectivity: ConnectivityObserver,
    private val thumbnails: DocumentThumbnails,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val failedCount = MutableStateFlow(0)
    private val mutableState = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    private var libraryJob: Job? = null

    init {
        observeLibrary()
        observeSearch()
        observeBackup()
        viewModelScope.launch {
            // Fetch what other devices changed while the app was closed. Without an account there is nothing to fetch.
            val signedIn = backupRepository.status.map { it.enabled }.catch { emit(false) }.first()
            // A background refresh that fails stays quiet: everything works offline.
            if (signedIn) documentRepository.refresh()
        }
    }

    private fun observeLibrary() {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            documentRepository.observeDocuments("")
                .catch { error ->
                    ScanovaLog.error(TAG, "home_load_failed", error)
                    mutableState.update { it.copy(isLoading = false, loadFailed = true) }
                }
                .collect { documents ->
                    failedCount.value = documents.count { it.syncState == SyncState.FAILED }
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            loadFailed = false,
                            recent = recentDocuments(documents),
                            documentCount = documents.size,
                        )
                    }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            query
                // Wait for a pause in typing before searching.
                .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MILLIS }
                .map { it.trim() }
                .distinctUntilChanged()
                .flatMapLatest { words ->
                    if (words.isEmpty()) {
                        flowOf(null)
                    } else {
                        documentRepository.observeDocuments(words)
                            .map { SearchAnswer(words, it) }
                            .catch { error ->
                                ScanovaLog.warn(TAG, "home_search_failed", error)
                                emit(SearchAnswer(words, emptyList(), failed = true))
                            }
                    }
                }
                .collect { answer -> mutableState.update { it.copy(searchAnswer = answer) } }
        }
    }

    private fun observeBackup() {
        viewModelScope.launch {
            combine(backupRepository.status, failedCount, connectivity.isOnline, ::backupSummary)
                .distinctUntilChanged()
                .catch { error -> ScanovaLog.warn(TAG, "backup_status_failed", error) }
                .collect { summary -> mutableState.update { it.copy(backup = summary) } }
        }
    }

    suspend fun cover(document: Document) = thumbnails.cover(document)

    fun onQueryChange(value: String) {
        query.value = value
        mutableState.update { it.copy(query = value) }
    }

    fun onRetryLoad() {
        mutableState.update { it.copy(isLoading = true, loadFailed = false) }
        observeLibrary()
    }

    private companion object {
        const val TAG = "Home"
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
