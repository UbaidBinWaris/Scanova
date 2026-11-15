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
import app.scanova.core.common.coroutines.ApplicationScope
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.model.Document
import app.scanova.core.data.model.DocumentPage
import app.scanova.feature.documents.navigation.ViewerDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ViewerContent {
    data object Loading : ViewerContent

    /** Pages are available; [aspectRatios] holds width ÷ height of each. */
    data class Pages(val aspectRatios: List<Float>) : ViewerContent

    /** A document from another device on an Android version that cannot preview it in memory. */
    data object PreviewUnsupported : ViewerContent

    data class Failed(val error: AppError) : ViewerContent
}

enum class ViewerDialog { RENAME, TAGS, DELETE, SHARE_WARNING }

data class ViewerUiState(
    val document: Document? = null,
    val content: ViewerContent = ViewerContent.Loading,
    val dialog: ViewerDialog? = null,
    /** A share, export or edit is in progress. */
    val isWorking: Boolean = false,
    val actionError: AppError? = null,
    /** A temporary decrypted copy is ready: the screen opens the share sheet for it. */
    val shareFile: File? = null,
    val copySaved: Boolean = false,
    /** The document is gone (deleted here or elsewhere): the screen closes. */
    val closed: Boolean = false,
)

/** State holder for one open document. */
@HiltViewModel
internal class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    private val dispatchers: AppDispatchers,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val documentId: String = savedStateHandle.toRoute<ViewerDestination>().documentId

    private var localPages: List<DocumentPage> = emptyList()
    private var pdf: InMemoryPdf? = null

    /** The temporary plaintext copy made for a share, until it has been deleted. */
    private var pendingExport: File? = null

    private val pageCache = object : LruCache<Int, Bitmap>(PAGE_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    private val mutableState = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            documentRepository.observeDocument(documentId)
                .catch { mutableState.update { it.copy(content = ViewerContent.Failed(AppError.LocalStorage)) } }
                .collect { document ->
                    mutableState.update { it.copy(document = document ?: it.document, closed = document == null) }
                }
        }
        loadContent()
    }

    private fun loadContent() {
        mutableState.update { it.copy(content = ViewerContent.Loading) }
        viewModelScope.launch {
            val content = try {
                val pages = documentRepository.observePages(documentId).first()
                when {
                    // Scanned here: the encrypted page images are shown directly.
                    pages.isNotEmpty() -> {
                        localPages = pages
                        ViewerContent.Pages(pages.map { if (it.height > 0) it.width.toFloat() / it.height else 0.7f })
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> openPdf()
                    else -> ViewerContent.PreviewUnsupported
                }
            } catch (e: Exception) {
                ScanovaLog.warn(TAG, "viewer_load_failed", e)
                ViewerContent.Failed(AppError.LocalStorage)
            }
            mutableState.update { it.copy(content = content) }
        }
    }

    private suspend fun openPdf(): ViewerContent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ViewerContent.PreviewUnsupported
        return when (val bytes = documentRepository.readPdf(documentId) { it.readBytes() }) {
            is AppResult.Failure -> ViewerContent.Failed(bytes.error)
            is AppResult.Success -> withContext(dispatchers.default) {
                val opened = InMemoryPdf(bytes.value)
                pdf = opened
                ViewerContent.Pages(opened.aspectRatios())
            }
        }
    }

    fun onRetryLoad() = loadContent()

    /** Decodes or renders one page, at most [maxWidth] pixels wide. Null when it cannot be shown. */
    suspend fun pageBitmap(index: Int, maxWidth: Int): Bitmap? {
        pageCache.get(index)?.let { return it }
        val width = maxWidth.coerceIn(MIN_PAGE_WIDTH, MAX_PAGE_WIDTH)
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
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "page_render_failed", e)
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        pageCache.put(index, bitmap)
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

    // ── Dialogs ─────────────────────────────────────────────────────────

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

    // ── Share and export: the only places plaintext leaves the app ──────

    /** The user confirmed that an unencrypted copy may be handed to another app. */
    fun onShareConfirmed() {
        mutableState.update { it.copy(dialog = null, isWorking = true, actionError = null) }
        viewModelScope.launch {
            when (val result = documentRepository.exportForSharing(documentId)) {
                is AppResult.Success -> {
                    pendingExport = result.value
                    mutableState.update { it.copy(isWorking = false, shareFile = result.value) }
                }
                is AppResult.Failure -> mutableState.update { it.copy(isWorking = false, actionError = result.error) }
            }
        }
    }

    /** The share sheet has been opened for the file; do not open it twice. */
    fun onShareStarted() {
        mutableState.update { it.copy(shareFile = null) }
    }

    /** The share sheet has returned: the temporary copy is deleted now. */
    fun onShareFinished() = deletePendingExport()

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

    fun onMessageShown() {
        mutableState.update { it.copy(copySaved = false, actionError = null) }
    }

    private fun deletePendingExport() {
        val file = pendingExport ?: return
        pendingExport = null
        // Not tied to this screen: the copy must go even if the screen is closed meanwhile.
        applicationScope.launch { documentRepository.deleteExport(file) }
    }

    override fun onCleared() {
        deletePendingExport()
        pageCache.evictAll()
        val opened = pdf
        pdf = null
        if (opened != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { opened.close() }
        }
    }

    private companion object {
        const val TAG = "Viewer"
        const val MIN_PAGE_WIDTH = 480
        const val MAX_PAGE_WIDTH = 1600
        val PAGE_CACHE_BYTES = max(24, (Runtime.getRuntime().maxMemory() / 8 / 1024 / 1024).toInt()) * 1024 * 1024
    }
}
