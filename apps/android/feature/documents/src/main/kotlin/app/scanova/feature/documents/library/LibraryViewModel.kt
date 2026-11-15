package app.scanova.feature.documents.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.model.Document
import app.scanova.core.data.settings.LibraryLayout
import app.scanova.core.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val isLoading: Boolean = true,
    /** The local database could not be read. */
    val loadFailed: Boolean = false,
    val query: String = "",
    val documents: List<Document> = emptyList(),
    val layout: LibraryLayout = LibraryLayout.LIST,
    val isRefreshing: Boolean = false,
    val refreshError: AppError? = null,
    val pendingSyncCount: Int = 0,
)

/** State holder for the library: the list of documents, search and pull-to-refresh. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()

    /** Decoded first pages, a few megabytes at most; evicted least-recently-used. */
    private val covers = object : LruCache<String, Bitmap>(COVER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        observeDocuments()
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                mutableState.update { it.copy(layout = settings.libraryLayout) }
            }
        }
        viewModelScope.launch {
            documentRepository.observePendingSyncCount()
                .catch { emit(0) }
                .collect { count -> mutableState.update { it.copy(pendingSyncCount = count) } }
        }
        // Fetch what other devices changed while the app was closed.
        refresh(showIndicator = false)
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeDocuments() {
        viewModelScope.launch {
            query
                // Wait for a pause in typing before searching.
                .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS }
                .distinctUntilChanged()
                .flatMapLatest { documentRepository.observeDocuments(it) }
                .catch { error ->
                    ScanovaLog.error(TAG, "library_load_failed", error)
                    mutableState.update { it.copy(isLoading = false, loadFailed = true) }
                }
                .collect { documents ->
                    mutableState.update { it.copy(isLoading = false, loadFailed = false, documents = documents) }
                }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
        mutableState.update { it.copy(query = value) }
    }

    fun onToggleLayout() {
        val next = if (mutableState.value.layout == LibraryLayout.LIST) LibraryLayout.GRID else LibraryLayout.LIST
        viewModelScope.launch { settingsRepository.setLibraryLayout(next) }
    }

    fun onRefresh() = refresh(showIndicator = true)

    fun onRetryLoad() {
        mutableState.update { it.copy(isLoading = true, loadFailed = false) }
        observeDocuments()
    }

    private fun refresh(showIndicator: Boolean) {
        if (mutableState.value.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = showIndicator, refreshError = null) }
        viewModelScope.launch {
            val result = documentRepository.refresh()
            mutableState.update {
                it.copy(
                    isRefreshing = false,
                    // A background refresh that fails stays quiet: the library works offline.
                    refreshError = if (showIndicator) (result as? AppResult.Failure)?.error else null,
                )
            }
        }
    }

    /** The first page of a document scanned on this phone, small, or null when there is none. */
    suspend fun cover(documentId: String): Bitmap? {
        covers.get(documentId)?.let { return it }
        val jpeg = (documentRepository.loadCoverImage(documentId) as? AppResult.Success)?.value ?: return null
        val bitmap = withContext(dispatchers.default) { decodeSmall(jpeg) } ?: return null
        covers.put(documentId, bitmap)
        return bitmap
    }

    private fun decodeSmall(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_LONG_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val TAG = "Library"
        const val SEARCH_DEBOUNCE_MILLIS = 250L
        const val COVER_LONG_EDGE = 320
        const val COVER_CACHE_BYTES = 12 * 1024 * 1024
    }
}
