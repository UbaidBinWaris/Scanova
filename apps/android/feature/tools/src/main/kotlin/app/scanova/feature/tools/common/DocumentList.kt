package app.scanova.feature.tools.common

import android.graphics.Bitmap
import android.util.LruCache
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.export.ExportBitmaps
import app.scanova.core.data.model.Document
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** The library as a document picker shows it. */
internal sealed interface DocumentListState {
    data object Loading : DocumentListState

    /** [query] is what was searched for ([documents] is empty either because the library is, or nothing matched). */
    data class Ready(val documents: List<Document>, val query: String) : DocumentListState

    data class Failed(val error: AppError) : DocumentListState
}

/** Searchable list of documents for the tools' pickers, owned by a screen's ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DocumentList(documents: DocumentRepository, scope: CoroutineScope) {
    private val mutableQuery = MutableStateFlow("")
    private val attempt = MutableStateFlow(0)

    val query: StateFlow<String> = mutableQuery.asStateFlow()

    val state: StateFlow<DocumentListState> = attempt
        .flatMapLatest { mutableQuery }
        .flatMapLatest { query ->
            documents.observeDocuments(query)
                .map<List<Document>, DocumentListState> { DocumentListState.Ready(it, query) }
                .catch { emit(DocumentListState.Failed(AppError.LocalStorage)) }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DocumentListState.Loading)

    fun onQueryChange(value: String) {
        mutableQuery.value = value
    }

    fun onRetry() {
        attempt.value++
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** First pages of documents as small bitmaps, for pickers. Cached per screen. */
internal class CoverLoader @Inject constructor(
    private val documents: DocumentRepository,
    private val dispatchers: AppDispatchers,
) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The first page of a document scanned or imported on this phone, or null when there is none here. */
    suspend fun cover(documentId: String): Bitmap? {
        cache.get(documentId)?.let { return it }
        val jpeg = (documents.loadCoverImage(documentId) as? AppResult.Success)?.value ?: return null
        val bitmap = withContext(dispatchers.default) {
            runCatching {
                ExportBitmaps.decodeAtLeast(jpeg, COVER_LONG_EDGE)?.let { ExportBitmaps.fitWithin(it, COVER_LONG_EDGE) }
            }.getOrNull()
        } ?: return null
        cache.put(documentId, bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()

    private companion object {
        const val COVER_LONG_EDGE = 240
        const val CACHE_BYTES = 8 * 1024 * 1024
    }
}
