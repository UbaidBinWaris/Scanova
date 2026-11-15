package app.scanova.feature.scan.crop

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.scan.ScanRepository
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.imaging.DocumentDetector
import app.scanova.feature.scan.imaging.PageRenderer
import app.scanova.feature.scan.navigation.CropDestination
import app.scanova.feature.scan.ocr.PageTextRecognizer
import app.scanova.feature.scan.session.FinishedPage
import app.scanova.feature.scan.session.ScanFilter
import app.scanova.feature.scan.session.ScanSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CropProblem { LOAD_FAILED, SAVE_FAILED }

data class CropUiState(
    /** The photo at display size, with the chosen filter applied. */
    val preview: Bitmap? = null,
    /** The crop outline as fractions of the photo. */
    val outline: Quad? = null,
    val filter: ScanFilter = ScanFilter.ENHANCED,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val problem: CropProblem? = null,
    /** The page has been stored; the screen moves on to the review. */
    val done: Boolean = false,
)

/** State holder for the "adjust page" screen: crop outline and filter of one page. */
@HiltViewModel
internal class CropViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val session: ScanSession,
    private val scanRepository: ScanRepository,
    private val textRecognizer: PageTextRecognizer,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val pageId: String = savedStateHandle.toRoute<CropDestination>().pageId
    private val renderer = PageRenderer()
    private val detector = DocumentDetector()

    /** The untouched capture, kept for the final full-resolution render. */
    private var captureJpeg: ByteArray? = null

    /** The capture at display size, before any filter. */
    private var displayPhoto: Bitmap? = null
    private var previewJob: Job? = null

    private val mutableState = MutableStateFlow(CropUiState())
    val state: StateFlow<CropUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val page = session.page(pageId)
        if (page == null) {
            mutableState.update { it.copy(isLoading = false, problem = CropProblem.LOAD_FAILED) }
            return
        }
        mutableState.update { it.copy(outline = page.outline, filter = page.filter) }
        viewModelScope.launch {
            val photo = when (val loaded = scanRepository.loadCapture(pageId)) {
                is AppResult.Success -> {
                    captureJpeg = loaded.value
                    withContext(dispatchers.default) { Bitmaps.decodeSampled(loaded.value, DISPLAY_LONG_EDGE) }
                }
                is AppResult.Failure -> null
            }
            if (photo == null) {
                mutableState.update { it.copy(isLoading = false, problem = CropProblem.LOAD_FAILED) }
                return@launch
            }
            displayPhoto = photo
            mutableState.update { it.copy(preview = photo, isLoading = false) }
            refreshPreview(page.filter)
        }
    }

    /** A corner handle was dragged to [position] (fractions of the photo). */
    fun onCornerMoved(index: Int, position: Point2) {
        mutableState.update { current ->
            val outline = current.outline ?: return@update current
            val clamped = Point2(position.x.coerceIn(0.0, 1.0), position.y.coerceIn(0.0, 1.0))
            val moved = outline.withCorner(index, clamped)
            // A corner dragged past its neighbours would fold the page over itself.
            if (moved.isConvex() && moved.area() > MIN_OUTLINE_AREA) current.copy(outline = moved) else current
        }
    }

    /** Moves a corner by a small step; used by the accessibility actions on each handle. */
    fun onCornerNudged(index: Int, dx: Double, dy: Double) {
        val corner = mutableState.value.outline?.corners?.getOrNull(index) ?: return
        onCornerMoved(index, Point2(corner.x + dx, corner.y + dy))
    }

    fun onDetectAgain() {
        val photo = displayPhoto ?: return
        viewModelScope.launch {
            val detected = withContext(dispatchers.default) { detector.detect(photo) }
                ?: session.page(pageId)?.detectedOutline
                ?: return@launch
            mutableState.update { it.copy(outline = detected) }
        }
    }

    fun onUseWholePhoto() {
        mutableState.update { it.copy(outline = Quad.inset(1.0, 1.0, 0.0)) }
    }

    fun onFilterSelected(filter: ScanFilter) {
        if (mutableState.value.filter == filter) return
        mutableState.update { it.copy(filter = filter) }
        refreshPreview(filter)
    }

    private fun refreshPreview(filter: ScanFilter) {
        val photo = displayPhoto ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val preview = withContext(dispatchers.default) { renderer.applyFilter(photo, filter) }
            mutableState.update { if (it.filter == filter) it.copy(preview = preview) else it }
        }
    }

    fun onConfirm() {
        val current = mutableState.value
        val outline = current.outline
        val jpeg = captureJpeg
        if (current.isSaving || outline == null || jpeg == null) return
        mutableState.update { it.copy(isSaving = true, problem = null) }
        viewModelScope.launch {
            val finished = withContext(dispatchers.default) { renderAndStore(jpeg, outline, current.filter) }
            if (finished == null) {
                mutableState.update { it.copy(isSaving = false, problem = CropProblem.SAVE_FAILED) }
                return@launch
            }
            session.update(pageId) { it.copy(outline = outline, filter = current.filter, finished = finished) }
            mutableState.update { it.copy(isSaving = false, done = true) }
        }
    }

    /** Leaving without confirming drops a page that was never finished (a fresh capture). */
    fun onAbandon() {
        val page = session.page(pageId) ?: return
        if (page.finished == null) {
            session.remove(pageId)
            viewModelScope.launch { scanRepository.discardPage(pageId) }
        }
    }

    private suspend fun renderAndStore(jpeg: ByteArray, outline: Quad, filter: ScanFilter): FinishedPage? {
        val photo = Bitmaps.decodeSampled(jpeg, CAPTURE_LONG_EDGE) ?: return null
        var flat: Bitmap? = null
        var page: Bitmap? = null
        try {
            flat = renderer.flatten(photo, outline, PAGE_LONG_EDGE)
            page = renderer.applyFilter(flat, filter)
            val stored = scanRepository.storePage(pageId, Bitmaps.toJpeg(page, PAGE_JPEG_QUALITY))
            if (stored is AppResult.Failure) return null
            // Recognition reads the unfiltered page: thresholding can erase thin strokes.
            val text = textRecognizer.recognize(flat)
            return FinishedPage(
                width = page.width,
                height = page.height,
                ocrText = text,
                thumbnail = Bitmaps.thumbnail(page, THUMBNAIL_LONG_EDGE),
            )
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            photo.recycle()
            if (page !== flat) page?.recycle()
            flat?.recycle()
        }
    }

    private companion object {
        const val DISPLAY_LONG_EDGE = 1280
        const val CAPTURE_LONG_EDGE = 2560
        const val PAGE_LONG_EDGE = 2400
        const val THUMBNAIL_LONG_EDGE = 360
        const val PAGE_JPEG_QUALITY = 85
        const val MIN_OUTLINE_AREA = 0.01
    }
}
