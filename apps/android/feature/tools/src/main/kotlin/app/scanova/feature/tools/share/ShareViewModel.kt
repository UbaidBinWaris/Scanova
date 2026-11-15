package app.scanova.feature.tools.share

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.coroutines.ApplicationScope
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.export.DocumentExporter
import app.scanova.core.data.export.ExportBundle
import app.scanova.core.data.export.ExportEstimate
import app.scanova.core.data.export.ExportFormat
import app.scanova.core.data.export.ExportSource
import app.scanova.core.data.export.ExportSummary
import app.scanova.core.data.export.ImageQuality
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.WorkProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ShareContent {
    data object Loading : ShareContent

    data class Failed(val error: AppError) : ShareContent

    data object Ready : ShareContent
}

/** What the files are being prepared for. */
internal enum class ShareAction { SHARE, SAVE }

internal data class ShareUiState(
    val content: ShareContent = ShareContent.Loading,
    val sources: List<ExportSource> = emptyList(),
    val format: ExportFormat = ExportFormat.PDF,
    val quality: ImageQuality = ImageQuality.HIGH,
    val summary: ExportSummary? = null,
    /** Files are being written for this action. */
    val working: ShareAction? = null,
    val progress: WorkProgress = WorkProgress(),
    val error: AppError? = null,
    /** Files are ready: the screen opens the share sheet for them once. */
    val readyToShare: ExportBundle? = null,
    /** Files are ready: the screen asks where to save them once. */
    val readyToSave: ExportBundle? = null,
    /** How many files were just saved to the device. */
    val savedCount: Int? = null,
    /** The share is over: the sheet closes. */
    val closed: Boolean = false,
) {
    val pageCount: Int get() = sources.sumOf { it.pageCount }

    fun isAvailable(format: ExportFormat): Boolean = when (format) {
        ExportFormat.PDF -> sources.isNotEmpty()
        ExportFormat.TEXT -> sources.any { !it.text.isEmpty }
        ExportFormat.IMAGES, ExportFormat.LONG_IMAGE -> sources.isNotEmpty() && sources.all { it.canExportImages }
    }
}

/**
 * The share sheet: choose a format and quality, then hand temporary,
 * unencrypted copies to another app or save them where the user picks. The
 * copies are deleted as soon as the share returns, the save is written, or
 * the sheet goes away; [DocumentExporter] keeps them in the cache directory
 * that is also swept at every app start.
 */
@HiltViewModel
internal class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: DocumentExporter,
    private val dispatchers: AppDispatchers,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = mutableState.asStateFlow()

    private var documentIds: List<String>? = null
    private var initialFormat: ExportFormat? = null
    private var work: Job? = null

    /** The plaintext copies of the current export, until they are deleted. */
    private var pending: ExportBundle? = null

    /** Loads the documents once; later calls (recomposition, rotation) do nothing. */
    fun start(ids: List<String>, format: ExportFormat?) {
        if (documentIds != null) return
        documentIds = ids
        initialFormat = format
        load()
    }

    fun onRetryLoad() = load()

    private fun load() {
        val ids = documentIds ?: return
        mutableState.update { it.copy(content = ShareContent.Loading) }
        viewModelScope.launch {
            when (val described = exporter.describe(ids)) {
                is AppResult.Failure -> mutableState.update { it.copy(content = ShareContent.Failed(described.error)) }
                is AppResult.Success -> {
                    val sources = described.value
                    if (sources.isEmpty()) {
                        mutableState.update { it.copy(content = ShareContent.Failed(AppError.NotFound)) }
                        return@launch
                    }
                    mutableState.update { current ->
                        val ready = current.copy(content = ShareContent.Ready, sources = sources)
                        val format = initialFormat?.takeIf(ready::isAvailable) ?: ExportFormat.PDF
                        ready.withChoice(format, ready.quality)
                    }
                }
            }
        }
    }

    fun onFormat(format: ExportFormat) = mutableState.update { current ->
        if (current.working != null || !current.isAvailable(format)) current else current.withChoice(format, current.quality)
    }

    fun onQuality(quality: ImageQuality) = mutableState.update { current ->
        if (current.working != null) current else current.withChoice(current.format, quality)
    }

    private fun ShareUiState.withChoice(format: ExportFormat, quality: ImageQuality) = copy(
        format = format,
        quality = quality,
        summary = ExportEstimate.summarize(format, quality, sources),
        error = null,
        savedCount = null,
    )

    fun onShare() = prepare(ShareAction.SHARE)

    fun onSave() = prepare(ShareAction.SAVE)

    private fun prepare(action: ShareAction) {
        val current = state.value
        if (current.working != null || current.content != ShareContent.Ready) return
        discardPending()
        mutableState.update { it.copy(working = action, progress = WorkProgress(), error = null, savedCount = null) }
        work = viewModelScope.launch {
            val result = exporter.export(
                sources = current.sources,
                format = current.format,
                quality = current.quality,
                pageHeading = { context.getString(R.string.feature_tools_text_page_heading, it) },
                onProgress = { done, total -> mutableState.update { it.copy(progress = WorkProgress(done, total)) } },
            )
            when (result) {
                is AppResult.Failure -> mutableState.update { it.copy(working = null, error = result.error) }
                is AppResult.Success -> {
                    pending = result.value
                    mutableState.update {
                        when (action) {
                            ShareAction.SHARE -> it.copy(working = null, readyToShare = result.value)
                            ShareAction.SAVE -> it.copy(working = null, readyToSave = result.value)
                        }
                    }
                }
            }
        }
    }

    /** Stops an export that is being written; what was written is removed. */
    fun onStop() {
        work?.cancel()
        work = null
        mutableState.update { it.copy(working = null, progress = WorkProgress()) }
    }

    // ── Share ───────────────────────────────────────────────────────────

    /** The share sheet has been opened; never open it twice for the same files. */
    fun onShareLaunched() = mutableState.update { it.copy(readyToShare = null) }

    /** The share sheet returned: the copies go, and so does this sheet. */
    fun onShareFinished() {
        discardPending()
        mutableState.update { it.copy(closed = true) }
    }

    /** The share sheet could not be opened. */
    fun onShareFailed() {
        discardPending()
        mutableState.update { it.copy(error = AppError.Unexpected("share_unavailable")) }
    }

    // ── Save to device ──────────────────────────────────────────────────

    fun onSaveLaunched() = mutableState.update { it.copy(readyToSave = null) }

    /** The user backed out of choosing where to save. */
    fun onSaveCancelled() = discardPending()

    /** Saves the single exported file to [target] (from the system's "create document"). */
    fun onSaveToFile(target: Uri?) {
        val bundle = pending
        if (target == null || bundle == null || bundle.files.size != 1) {
            discardPending()
            return
        }
        save(bundle) { listOf(bundle.files.single() to target) }
    }

    /** Saves every exported file into the folder [tree] (from the system's folder picker). */
    fun onSaveToFolder(tree: Uri?) {
        val bundle = pending
        if (tree == null || bundle == null) {
            discardPending()
            return
        }
        save(bundle) {
            val resolver = context.contentResolver
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            bundle.files.map { file ->
                file to (DocumentsContract.createDocument(resolver, folder, bundle.mimeType, file.name) ?: error("create_failed"))
            }
        }
    }

    private fun save(bundle: ExportBundle, targets: () -> List<Pair<File, Uri>>) {
        mutableState.update { it.copy(working = ShareAction.SAVE, progress = WorkProgress(0, bundle.files.size), error = null) }
        work = viewModelScope.launch {
            val saved = try {
                val pairs = withContext(dispatchers.io) { targets() }
                var count = 0
                for ((file, target) in pairs) {
                    withContext(dispatchers.io) {
                        val output = context.contentResolver.openOutputStream(target) ?: error("open_failed")
                        output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
                    }
                    count++
                    mutableState.update { it.copy(progress = WorkProgress(count, pairs.size)) }
                }
                count
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                ScanovaLog.warn(TAG, "save_failed", e)
                null
            } finally {
                discardPending()
            }
            mutableState.update {
                if (saved != null) {
                    it.copy(working = null, savedCount = saved)
                } else {
                    it.copy(working = null, error = AppError.Unexpected("save_failed"))
                }
            }
        }
    }

    // ── Closing ─────────────────────────────────────────────────────────

    /** The sheet is being dismissed: stop any work and delete the copies. */
    fun onClose() {
        work?.cancel()
        work = null
        discardPending()
    }

    private fun discardPending() {
        val bundle = pending ?: return
        pending = null
        // Not tied to this screen: the copies must go even if it closes meanwhile.
        applicationScope.launch { exporter.discard(bundle) }
    }

    override fun onCleared() {
        work?.cancel()
        discardPending()
    }

    private companion object {
        const val TAG = "Share"
    }
}
