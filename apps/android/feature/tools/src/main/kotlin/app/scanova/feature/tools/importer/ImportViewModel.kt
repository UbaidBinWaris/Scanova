package app.scanova.feature.tools.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.documents.PageDraft
import app.scanova.core.data.model.AppTool
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.CreatedDocument
import app.scanova.feature.tools.common.DocumentBuilder
import app.scanova.feature.tools.common.ToolError
import app.scanova.feature.tools.common.ToolException
import app.scanova.feature.tools.common.WorkProgress
import app.scanova.feature.tools.common.formatDate
import app.scanova.feature.tools.common.toToolError
import app.scanova.feature.tools.navigation.ToolFlowDestination
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

    /** Importing file [fileNumber] of [fileCount]; [progress] counts its pages (or the pictures). */
    data class Working(val fileName: String?, val fileNumber: Int, val fileCount: Int, val progress: WorkProgress) : ImportStage

    data class Failed(val error: ToolError) : ImportStage

    /** Several documents were made, or some files were skipped: shown before leaving. */
    data class Done(val documents: List<CreatedDocument>, val skipped: List<ToolError>) : ImportStage
}

/** Where to go when the import is over. */
internal sealed interface ImportExit {
    /** Pictures became a document: straight to the page editor to crop and filter. */
    data class Edit(val documentId: String) : ImportExit

    data class Open(val documentId: String) : ImportExit

    data object Close : ImportExit
}

internal data class ImportUiState(
    val tool: AppTool,
    val stage: ImportStage = ImportStage.Picking,
    /** The picker should be opened now (once). */
    val launchPicker: Boolean = false,
    val exit: ImportExit? = null,
)

/**
 * Imports pictures (photo picker) or files (PDFs and pictures from the system
 * file picker). Pictures picked together become one document; each PDF
 * becomes a document of its own, titled after its file. A file that cannot be
 * read is skipped and reported; nothing half-imported is kept.
 */
@HiltViewModel
internal class ImportViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val builder: DocumentBuilder,
    private val clock: TimeSource,
) : ViewModel() {

    private val tool = savedStateHandle.toRoute<ToolFlowDestination>().tool

    private val mutableState = MutableStateFlow(
        ImportUiState(tool = tool, launchPicker = savedStateHandle.get<Boolean>(KEY_PICKER_SHOWN) != true),
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
                when (tool) {
                    AppTool.IMPORT_IMAGES -> importPictures(uris)
                    else -> importFiles(uris)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ImportStage.Failed(e.toToolError())
            }
            val documents = (stage as? ImportStage.Done)?.documents.orEmpty()
            val exit = when {
                stage !is ImportStage.Done || stage.skipped.isNotEmpty() || documents.size != 1 -> null
                tool == AppTool.IMPORT_IMAGES -> ImportExit.Edit(documents.single().id)
                else -> ImportExit.Open(documents.single().id)
            }
            mutableState.update { it.copy(stage = stage, exit = exit) }
        }
    }

    /** Pick again after a failure. */
    fun onRetry() {
        savedStateHandle[KEY_PICKER_SHOWN] = false
        mutableState.update { it.copy(stage = ImportStage.Picking, launchPicker = true) }
    }

    /** Stops a running import (what it staged is discarded) and leaves. */
    fun onStop() {
        job?.cancel()
        mutableState.update { it.copy(exit = ImportExit.Close) }
    }

    fun onExitHandled() = mutableState.update { it.copy(exit = null) }

    // ── Importing ───────────────────────────────────────────────────────

    private suspend fun importPictures(uris: List<Uri>): ImportStage {
        val skipped = mutableListOf<ToolError>()
        val created = importPictureDocument(uris, fileNumber = 1, fileCount = 1, skipped = skipped)
        return when {
            created != null -> ImportStage.Done(listOf(created), skipped)
            else -> ImportStage.Failed(skipped.firstOrNull() ?: ToolError.Unreadable(null))
        }
    }

    private suspend fun importFiles(uris: List<Uri>): ImportStage {
        val created = mutableListOf<CreatedDocument>()
        val skipped = mutableListOf<ToolError>()
        val pictures = mutableListOf<Uri>()
        val pdfs = mutableListOf<Pair<Uri, String?>>()
        for (uri in uris) {
            val name = builder.displayName(uri)
            val type = builder.mimeType(uri, name)
            when {
                type == DocumentBuilder.PDF_MIME -> pdfs += uri to name
                type != null && type.startsWith("image/") -> pictures += uri
                else -> skipped += ToolError.Unreadable(name)
            }
        }
        val fileCount = pdfs.size + if (pictures.isEmpty()) 0 else 1
        pdfs.forEachIndexed { index, (uri, name) ->
            report(name, index + 1, fileCount, WorkProgress())
            try {
                val pages = builder.stagePdf(uri) { done, total -> report(name, index + 1, fileCount, WorkProgress(done, total)) }
                val title = name?.let(::titleFromFileName) ?: importedTitle()
                created += CreatedDocument(builder.create(title, pages), title, pages.size)
            } catch (e: ToolException) {
                skipped += e.error
            }
        }
        if (pictures.isNotEmpty()) {
            importPictureDocument(pictures, fileNumber = fileCount, fileCount = fileCount, skipped = skipped)?.let { created += it }
        }
        return when {
            created.isNotEmpty() -> ImportStage.Done(created, skipped)
            else -> ImportStage.Failed(skipped.firstOrNull() ?: ToolError.Unreadable(null))
        }
    }

    /** All [uris] as pages of one new document; unreadable pictures are skipped and added to [skipped]. */
    private suspend fun importPictureDocument(
        uris: List<Uri>,
        fileNumber: Int,
        fileCount: Int,
        skipped: MutableList<ToolError>,
    ): CreatedDocument? {
        val pages = mutableListOf<PageDraft>()
        try {
            uris.forEachIndexed { index, uri ->
                report(null, fileNumber, fileCount, WorkProgress(index, uris.size))
                try {
                    pages += builder.stageImage(uri)
                } catch (e: ToolException) {
                    if (e.error is ToolError.Storage) throw e
                    skipped += e.error
                }
            }
            if (pages.isEmpty()) return null
            report(null, fileNumber, fileCount, WorkProgress(uris.size, uris.size))
            val title = importedTitle()
            return CreatedDocument(builder.create(title, pages), title, pages.size)
        } catch (e: Throwable) {
            builder.discard(pages)
            throw e
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

/** A document title from a file name: without its extension and surrounding blanks; null when nothing is left. */
internal fun titleFromFileName(name: String): String? =
    name.substringBeforeLast('.', name).replace('_', ' ').trim().takeIf { it.isNotEmpty() }
