package app.scanova.feature.tools.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.util.TimeSource
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.CreatedDocument
import app.scanova.feature.tools.common.DocumentBuilder
import app.scanova.feature.tools.common.ToolError
import app.scanova.feature.tools.common.ToolException
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

internal sealed interface ImportStage {
    /** The system picker is (about to be) open. */
    data object Picking : ImportStage

    /** Importing file [fileNumber] of [fileCount]; [progress] counts its pages. */
    data class Working(val fileName: String?, val fileNumber: Int, val fileCount: Int, val progress: WorkProgress) : ImportStage

    data class Failed(val error: ToolError) : ImportStage

    /**
     * Shown before leaving: the documents made from PDFs, the files that were
     * skipped, and [pictures] still to be scanned (content URIs as strings).
     */
    data class Done(val documents: List<CreatedDocument>, val skipped: List<ToolError>, val pictures: List<String>) : ImportStage
}

/** Where to go when the import is over. */
internal sealed interface ImportExit {
    data class Open(val documentId: String) : ImportExit

    /** Pictures were picked: they go to the scanner, which trims, straightens and enhances each like a photo it took. */
    data class ScanPictures(val uris: List<String>) : ImportExit

    data object Close : ImportExit
}

internal data class ImportUiState(
    val stage: ImportStage = ImportStage.Picking,
    /** The picker should be opened now (once). */
    val launchPicker: Boolean = false,
    val exit: ImportExit? = null,
)

/**
 * Imports files from the system file picker. Each PDF becomes a document of
 * its own, titled after its file; pictures are handed to the scanner's photo
 * import (they become pages of one scan, found and trimmed like camera
 * photos). A file that cannot be read is skipped and reported; nothing
 * half-imported is kept.
 */
@HiltViewModel
internal class ImportViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val builder: DocumentBuilder,
    private val clock: TimeSource,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        ImportUiState(launchPicker = savedStateHandle.get<Boolean>(KEY_PICKER_SHOWN) != true),
    )
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** The picker is open; after a process restart its result still arrives, so it is not opened again. */
    fun onPickerLaunched() {
        savedStateHandle[KEY_PICKER_SHOWN] = true
        mutableState.update { it.copy(launchPicker = false) }
    }

    /** No app can pick files (should not happen on a phone with Google's or the system's picker). */
    fun onPickerUnavailable() {
        mutableState.update { it.copy(launchPicker = false, stage = ImportStage.Failed(ToolError.Unreadable(null))) }
    }

    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            // Backed out of the picker: nothing to do here.
            if (state.value.stage == ImportStage.Picking) mutableState.update { it.copy(exit = ImportExit.Close) }
            return
        }
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val stage = try {
                importFiles(uris)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ImportStage.Failed(e.toToolError())
            }
            mutableState.update { it.copy(stage = stage, exit = exitAfter(stage)) }
        }
    }

    /** Pick again after a failure. */
    fun onRetry() {
        savedStateHandle[KEY_PICKER_SHOWN] = false
        mutableState.update { it.copy(stage = ImportStage.Picking, launchPicker = true) }
    }

    /** "Scan N pictures" on the result: on to the scanner with the pictures. */
    fun onScanPictures() {
        val pictures = (state.value.stage as? ImportStage.Done)?.pictures.orEmpty()
        mutableState.update { it.copy(exit = if (pictures.isEmpty()) ImportExit.Close else ImportExit.ScanPictures(pictures)) }
    }

    /** Stops a running import (what it staged is discarded) and leaves. */
    fun onStop() {
        job?.cancel()
        mutableState.update { it.copy(exit = ImportExit.Close) }
    }

    fun onExitHandled() = mutableState.update { it.copy(exit = null) }

    // ── Importing ───────────────────────────────────────────────────────

    private suspend fun importFiles(uris: List<Uri>): ImportStage {
        val created = mutableListOf<CreatedDocument>()
        val skipped = mutableListOf<ToolError>()
        val pictures = mutableListOf<String>()
        val pdfs = mutableListOf<Pair<Uri, String?>>()
        for (uri in uris) {
            val name = builder.displayName(uri)
            when (ImportedFileKind.of(builder.mimeType(uri, name))) {
                ImportedFileKind.PDF -> pdfs += uri to name
                ImportedFileKind.PICTURE -> pictures += uri.toString()
                ImportedFileKind.OTHER -> skipped += ToolError.Unreadable(name)
            }
        }
        pdfs.forEachIndexed { index, (uri, name) ->
            report(name, index + 1, pdfs.size, WorkProgress())
            try {
                val pages = builder.stagePdf(uri) { done, total -> report(name, index + 1, pdfs.size, WorkProgress(done, total)) }
                val title = name?.let(::titleFromFileName) ?: importedTitle()
                created += CreatedDocument(builder.create(title, pages), title, pages.size)
            } catch (e: ToolException) {
                skipped += e.error
            }
        }
        return when {
            created.isNotEmpty() || pictures.isNotEmpty() -> ImportStage.Done(created, skipped, pictures)
            else -> ImportStage.Failed(skipped.firstOrNull() ?: ToolError.Unreadable(null))
        }
    }

    private fun report(fileName: String?, fileNumber: Int, fileCount: Int, progress: WorkProgress) {
        mutableState.update { it.copy(stage = ImportStage.Working(fileName, fileNumber, fileCount, progress)) }
    }

    private fun importedTitle(): String = context.getString(R.string.feature_tools_import_title, formatDate(clock.now()))

    override fun onCleared() {
        job?.cancel()
    }

    private companion object {
        const val KEY_PICKER_SHOWN = "picker_shown"
    }
}

/** What a picked file is to the importer, from its MIME type. */
internal enum class ImportedFileKind {
    PDF,
    PICTURE,
    OTHER,
    ;

    companion object {
        fun of(mimeType: String?): ImportedFileKind = when {
            mimeType == null -> OTHER
            mimeType.equals(DocumentBuilder.PDF_MIME, ignoreCase = true) -> PDF
            mimeType.startsWith("image/", ignoreCase = true) -> PICTURE
            else -> OTHER
        }
    }
}

/**
 * Where an import goes on its own: straight to the scanner when only pictures
 * were picked, straight to the document when exactly one PDF was imported;
 * otherwise the result is shown first (several documents, skipped files, or
 * documents and pictures together).
 */
internal fun exitAfter(stage: ImportStage): ImportExit? {
    if (stage !is ImportStage.Done || stage.skipped.isNotEmpty()) return null
    return when {
        stage.documents.isEmpty() && stage.pictures.isNotEmpty() -> ImportExit.ScanPictures(stage.pictures)
        stage.pictures.isEmpty() && stage.documents.size == 1 -> ImportExit.Open(stage.documents.single().id)
        else -> null
    }
}

/** A document title from a file name: without its extension and surrounding blanks; null when nothing is left. */
internal fun titleFromFileName(name: String): String? =
    name.substringBeforeLast('.', name).replace('_', ' ').trim().takeIf { it.isNotEmpty() }
