package app.scanova.feature.tools.text

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.coroutines.ApplicationScope
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.export.DocumentExporter
import app.scanova.core.data.export.ExportBundle
import app.scanova.core.data.export.ExportNames
import app.scanova.core.data.export.TextSection
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.ShareIntents
import app.scanova.feature.tools.navigation.ExtractTextDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface TextContent {
    data object Loading : TextContent

    data class Failed(val error: AppError) : TextContent

    /** [sections] is empty when nothing was recognised in the document. */
    data class Ready(val title: String, val sections: List<TextSection>, val plainText: String) : TextContent
}

internal data class ExtractTextUiState(
    val content: TextContent = TextContent.Loading,
    val working: Boolean = false,
    /** Text to hand to the share sheet as it is (short texts). */
    val shareText: String? = null,
    /** A `.txt` copy to hand to the share sheet (long texts). */
    val shareFile: ExportBundle? = null,
    val saved: Boolean = false,
    val actionError: AppError? = null,
)

/** A document's recognised text: shown per page, copied, shared or saved as a `.txt` file. */
@HiltViewModel
internal class ExtractTextViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val exporter: DocumentExporter,
    private val dispatchers: AppDispatchers,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val documentId = savedStateHandle.toRoute<ExtractTextDestination>().documentId

    private val mutableState = MutableStateFlow(ExtractTextUiState())
    val state: StateFlow<ExtractTextUiState> = mutableState.asStateFlow()

    /** The temporary `.txt` copy made for a share, until it is deleted. */
    private var pendingFile: ExportBundle? = null

    init {
        load()
    }

    fun onRetry() = load()

    private fun load() {
        mutableState.update { it.copy(content = TextContent.Loading) }
        viewModelScope.launch {
            val content = when (val source = exporter.describe(documentId)) {
                is AppResult.Failure -> TextContent.Failed(source.error)
                is AppResult.Success -> TextContent.Ready(
                    title = source.value.title,
                    sections = source.value.text.sections(),
                    plainText = source.value.text.toPlainText { context.getString(R.string.feature_tools_text_page_heading, it) },
                )
            }
            mutableState.update { it.copy(content = content) }
        }
    }

    /** The name suggested when saving the text as a file. */
    fun fileName(): String = ExportNames.text((state.value.content as? TextContent.Ready)?.title.orEmpty())

    fun onShare() {
        val ready = state.value.content as? TextContent.Ready ?: return
        if (state.value.working || ready.plainText.isEmpty()) return
        mutableState.update { it.copy(actionError = null, saved = false) }
        if (ready.plainText.length <= ShareIntents.MAX_INLINE_TEXT) {
            mutableState.update { it.copy(shareText = ready.plainText) }
            return
        }
        // Too long for an intent: share a temporary .txt file instead.
        mutableState.update { it.copy(working = true) }
        viewModelScope.launch {
            when (val bundle = exporter.exportText(ready.title, ready.plainText)) {
                is AppResult.Success -> {
                    pendingFile = bundle.value
                    mutableState.update { it.copy(working = false, shareFile = bundle.value) }
                }
                is AppResult.Failure -> mutableState.update { it.copy(working = false, actionError = bundle.error) }
            }
        }
    }

    /** The share sheet has been opened; do not open it twice. */
    fun onShareLaunched() = mutableState.update { it.copy(shareText = null, shareFile = null) }

    /** The share sheet returned (or could not open): a temporary copy is deleted now. */
    fun onShareFinished() = discardPending()

    fun onShareFailed() {
        discardPending()
        mutableState.update { it.copy(actionError = AppError.Unexpected("share_unavailable")) }
    }

    /** Writes the text to [target], a file the user created with the system's "save" screen. */
    fun onSaveTo(target: Uri?) {
        val ready = state.value.content as? TextContent.Ready ?: return
        if (target == null) return
        mutableState.update { it.copy(working = true, actionError = null, saved = false) }
        viewModelScope.launch {
            val ok = withContext(dispatchers.io) {
                try {
                    context.contentResolver.openOutputStream(target)?.use { it.write(ready.plainText.toByteArray(Charsets.UTF_8)) } != null
                } catch (e: IOException) {
                    ScanovaLog.warn(TAG, "text_save_failed", e)
                    false
                } catch (e: SecurityException) {
                    ScanovaLog.warn(TAG, "text_save_denied", e)
                    false
                }
            }
            mutableState.update {
                it.copy(working = false, saved = ok, actionError = if (ok) null else AppError.Unexpected("save_failed"))
            }
        }
    }

    fun onMessageShown() = mutableState.update { it.copy(saved = false) }

    private fun discardPending() {
        val bundle = pendingFile ?: return
        pendingFile = null
        applicationScope.launch { exporter.discard(bundle) }
    }

    override fun onCleared() = discardPending()

    private companion object {
        const val TAG = "ExtractText"
    }
}
