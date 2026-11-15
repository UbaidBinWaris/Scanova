package app.scanova.feature.scan.review

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.scan.ScanRepository
import app.scanova.core.data.scan.ScannedDocument
import app.scanova.core.data.scan.ScannedPage
import app.scanova.feature.scan.R
import app.scanova.feature.scan.ocr.TitleSuggester
import app.scanova.feature.scan.pdf.PdfWriter
import app.scanova.feature.scan.session.DraftPage
import app.scanova.feature.scan.session.ScanSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewPage(
    val id: String,
    val thumbnail: Bitmap,
)

data class ReviewUiState(
    val pages: List<ReviewPage> = emptyList(),
    val title: String = "",
    val titleMissing: Boolean = false,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val confirmingDiscard: Boolean = false,
    /** Set when the document was saved: the screen leaves the scan flow. */
    val savedDocumentId: String? = null,
    /** Set when the scan was discarded: the screen leaves the scan flow. */
    val discarded: Boolean = false,
)

/** State holder for the multi-page review screen. */
@HiltViewModel
internal class ReviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ScanSession,
    private val scanRepository: ScanRepository,
    private val clock: TimeSource,
) : ViewModel() {

    private val capturedAt = clock.now()
    private var titleEditedByUser = false

    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            session.pages.collect { drafts ->
                val finished = drafts.filter { it.finished != null }
                mutableState.update { current ->
                    current.copy(
                        pages = finished.map { ReviewPage(it.id, it.finished!!.thumbnail) },
                        // Until the user types a title, keep proposing one from the first page.
                        title = if (titleEditedByUser) current.title else suggestedTitle(finished),
                    )
                }
            }
        }
    }

    fun onTitleChange(value: String) {
        titleEditedByUser = true
        mutableState.update { it.copy(title = value, titleMissing = false) }
    }

    fun onMovePage(pageId: String, offset: Int) = session.move(pageId, offset)

    fun onDeletePage(pageId: String) {
        session.remove(pageId)
        viewModelScope.launch { scanRepository.discardPage(pageId) }
    }

    /** The next photo taken replaces this page. */
    fun onRetakePage(pageId: String) {
        session.replaceTargetId = pageId
    }

    fun onDiscardRequested() {
        // Nothing scanned, nothing to confirm.
        if (session.pages.value.isEmpty()) onDiscardConfirmed() else mutableState.update { it.copy(confirmingDiscard = true) }
    }

    fun onDiscardDismissed() {
        mutableState.update { it.copy(confirmingDiscard = false) }
    }

    fun onDiscardConfirmed() {
        val pageIds = session.clear()
        mutableState.update { it.copy(confirmingDiscard = false, discarded = true) }
        viewModelScope.launch { pageIds.forEach { scanRepository.discardPage(it) } }
    }

    fun onSave() {
        val current = mutableState.value
        if (current.isSaving) return
        val title = current.title.trim()
        if (title.isEmpty()) {
            mutableState.update { it.copy(titleMissing = true) }
            return
        }
        val drafts = session.pages.value
        val pages = drafts.filter { it.finished != null }
        if (pages.isEmpty()) return

        mutableState.update { it.copy(isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            val document = ScannedDocument(
                title = title,
                capturedAt = capturedAt,
                pages = pages.map { ScannedPage(it.id, it.finished!!.width, it.finished.height, it.finished.ocrText) },
            )
            val result = scanRepository.saveDocument(document) { output, readPage ->
                // Pages are decrypted and embedded one at a time, straight into the encrypted PDF.
                val writer = PdfWriter(output, capturedAt, title)
                pages.forEach { writer.addJpegPage(readPage(it.id)) }
                writer.finish()
            }
            when (result) {
                is AppResult.Success -> {
                    session.clear()
                    // Captures that never became pages are no longer needed.
                    drafts.filter { it.finished == null }.forEach { scanRepository.discardPage(it.id) }
                    mutableState.update { it.copy(isSaving = false, savedDocumentId = result.value) }
                }
                is AppResult.Failure -> mutableState.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    private fun suggestedTitle(pages: List<DraftPage>): String {
        val fromText = pages.firstOrNull()?.finished?.ocrText?.let(TitleSuggester::suggest)
        if (fromText != null) return fromText
        val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(capturedAt.atZone(ZoneId.systemDefault()))
        return context.getString(R.string.feature_scan_default_title, formatted)
    }
}
