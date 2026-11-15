package app.scanova.feature.documents.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.backup.BackupRepository
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.model.Document
import app.scanova.core.data.model.DocumentPage
import app.scanova.feature.documents.navigation.ViewerDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.OutputStream
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ViewerContent {
    data object Loading : ViewerContent

    /**
     * Pages are available; [aspectRatios] holds width ÷ height of each.
     * [onDevice]: the page images are on this phone (scanned or imported here), so the pages can be edited.
     * [version] changes whenever the pages change (an edit, a newer version from another device).
     */
    data class Pages(val aspectRatios: List<Float>, val onDevice: Boolean, val version: Int) : ViewerContent

    /** A document from another device on an Android version that cannot preview it in memory. */
    data object PreviewUnsupported : ViewerContent

    data class Failed(val error: AppError) : ViewerContent
}

internal enum class ViewerDialog { RENAME, TAGS, DELETE }

internal data class ViewerUiState(
    val document: Document? = null,
    val content: ViewerContent = ViewerContent.Loading,
    val dialog: ViewerDialog? = null,
    /** An export or edit is in progress. */
    val isWorking: Boolean = false,
    val actionError: AppError? = null,
    val copySaved: Boolean = false,
    /** The document is gone (deleted here or elsewhere): the screen closes. */
    val closed: Boolean = false,
    /** An account is signed in, so the document has a backup state worth showing. */
    val signedIn: Boolean = false,
) {
    /** The page editor works on page images stored on this phone. */
    val canEdit: Boolean get() = (content as? ViewerContent.Pages)?.onDevice == true
}

/** What decides the pages on screen; when it changes, they are loaded again. */
private data class ContentKey(val pages: List<DocumentPage>, val sizeBytes: Long?, val pageCount: Int, val attempt: Int)

/** State holder for one open document. */
@HiltViewModel
internal class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    backupRepository: BackupRepository,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val documentId: String = savedStateHandle.toRoute<ViewerDestination>().documentId

    private var localPages: List<DocumentPage> = emptyList()
    private var pdf: InMemoryPdf? = null
    private var version = 0
    private val attempt = MutableStateFlow(0)

    /** Rendered pages and thumbnails, keyed by version, page and width. */
    private val pageCache = object : LruCache<String, Bitmap>(PAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val mutableState = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = mutableState.asStateFlow()

    init {
        val document = documentRepository.observeDocument(documentId)
        viewModelScope.launch {
            document
                .catch { mutableState.update { it.copy(content = ViewerContent.Failed(AppError.LocalStorage)) } }
                .collect { current ->
                    mutableState.update { it.copy(document = current ?: it.document, closed = current == null) }
                }
        }
        viewModelScope.launch {
            backupRepository.status
                .map { it.enabled }
                .distinctUntilChanged()
                .catch { emit(false) }
                .collect { signedIn -> mutableState.update { it.copy(signedIn = signedIn) } }
        }
        viewModelScope.launch {
            // A rename changes neither the pages nor the file, so it never reloads what is on screen.
            combine(
                documentRepository.observePages(documentId),
                document.filterNotNull().map { it.sizeBytes to it.pageCount },
                attempt,
            ) { pages, (size, count), tries -> ContentKey(pages, size, count, tries) }
                .distinctUntilChanged()
                .catch { error ->
                    ScanovaLog.warn(TAG, "viewer_observe_failed", error)
                    mutableState.update { it.copy(content = ViewerContent.Failed(AppError.LocalStorage)) }
                }
                .collectLatest { key -> loadContent(key) }
        }
    }

    private suspend fun loadContent(key: ContentKey) {
        version += 1
        pageCache.evictAll()
        closePdf()
        // Pages already on screen stay there while their new version loads.
        if (mutableState.value.content !is ViewerContent.Pages) {
            mutableState.update { it.copy(content = ViewerContent.Loading) }
        }
        val content = try {
            when {
                // Scanned or imported here: the encrypted page images are shown directly.
                key.pages.isNotEmpty() -> {
                    localPages = key.pages
                    ViewerContent.Pages(
                        aspectRatios = key.pages.map { if (it.height > 0) it.width.toFloat() / it.height else DEFAULT_RATIO },
                        onDevice = true,
                        version = version,
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    localPages = emptyList()
                    openPdf()
                }
                else -> ViewerContent.PreviewUnsupported
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "viewer_load_failed", e)
            ViewerContent.Failed(AppError.LocalStorage)
        }
        mutableState.update { it.copy(content = content) }
    }

    private suspend fun openPdf(): ViewerContent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ViewerContent.PreviewUnsupported
        return when (val bytes = documentRepository.readPdf(documentId) { it.readBytes() }) {
            is AppResult.Failure -> ViewerContent.Failed(bytes.error)
            is AppResult.Success -> withContext(dispatchers.default) {
                val opened = InMemoryPdf(bytes.value)
                pdf = opened
                ViewerContent.Pages(opened.aspectRatios(), onDevice = false, version = version)
            }
        }
    }

    fun onRetryLoad() {
        mutableState.update { it.copy(content = ViewerContent.Loading) }
        attempt.update { it + 1 }
    }

    /** Decodes or renders one page, at most [maxWidth] pixels wide. Null when it cannot be shown. */
    suspend fun pageBitmap(index: Int, maxWidth: Int): Bitmap? {
        val width = maxWidth.coerceIn(MIN_PAGE_WIDTH, MAX_PAGE_WIDTH)
        val key = "$version:$index:$width"
        pageCache.get(key)?.let { return it }
        val bitmap = try {
            val page = localPages.getOrNull(index)
            if (page != null) {
                val jpeg = (documentRepository.loadPageImage(page.id) as? AppResult.Success)?.value ?: return null
                withContext(dispatchers.default) { decode(jpeg, width) }
            } else {
                val opened = pdf
                if (opened == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
                withContext(dispatchers.default) { opened.render(index, width) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "page_render_failed", e)
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        pageCache.put(key, bitmap)
        return bitmap
    }

    private fun decode(jpeg: ByteArray, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ── Dialogs and edits ───────────────────────────────────────────────

    fun onShowDialog(dialog: ViewerDialog) {
        mutableState.update { it.copy(dialog = dialog, actionError = null) }
    }

    fun onDismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun onRename(title: String) = edit { documentRepository.rename(documentId, title) }

    fun onSetTags(text: String) = edit {
        documentRepository.setTags(documentId, text.split(',', '\n', ';'))
    }

    fun onRetryUpload() = edit { documentRepository.retryUpload(documentId) }

    fun onDeleteConfirmed() = edit { documentRepository.delete(documentId) }

    private fun edit(action: suspend () -> AppResult<Unit>) {
        mutableState.update { it.copy(dialog = null, isWorking = true, actionError = null) }
        viewModelScope.launch {
            val result = action()
            mutableState.update { it.copy(isWorking = false, actionError = (result as? AppResult.Failure)?.error) }
        }
    }

    // ── Save a copy: plaintext leaves the app only where the user chose ──

    /** Writes the decrypted PDF to a location the user picked in the system file chooser. */
    fun onSaveCopyTo(output: OutputStream) {
        mutableState.update { it.copy(isWorking = true, actionError = null, copySaved = false) }
        viewModelScope.launch {
            val result = documentRepository.exportTo(documentId, output)
            mutableState.update {
                it.copy(
                    isWorking = false,
                    copySaved = result is AppResult.Success,
                    actionError = (result as? AppResult.Failure)?.error,
                )
            }
        }
    }

    /** The "copy saved" confirmation has been shown. */
    fun onMessageShown() {
        mutableState.update { it.copy(copySaved = false) }
    }

    private fun closePdf() {
        val opened = pdf ?: return
        pdf = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { opened.close() }
    }

    override fun onCleared() {
        pageCache.evictAll()
        closePdf()
    }

    private companion object {
        const val TAG = "Viewer"
        const val DEFAULT_RATIO = 0.7f
        const val MIN_PAGE_WIDTH = 96
        const val MAX_PAGE_WIDTH = 1600
        val PAGE_CACHE_BYTES = max(24, (Runtime.getRuntime().maxMemory() / 8 / 1024 / 1024).toInt()) * 1024 * 1024
    }
}
