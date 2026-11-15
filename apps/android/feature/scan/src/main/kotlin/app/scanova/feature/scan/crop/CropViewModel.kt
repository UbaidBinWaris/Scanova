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
import app.scanova.feature.scan.navigation.CropDestination
import app.scanova.feature.scan.session.PageFinisher
import app.scanova.feature.scan.session.ScanSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CropProblem { LOAD_FAILED, SAVE_FAILED, NOTHING_DETECTED }

data class CropUiState(
    /** The photo at display size, as taken (the look is chosen on the review screen). */
    val preview: Bitmap? = null,
    /** The crop outline as fractions of the photo. */
    val outline: Quad? = null,
    val isLoading: Boolean = true,
    val isDetecting: Boolean = false,
    val isSaving: Boolean = false,
    val problem: CropProblem? = null,
    /** The page has been stored; the screen moves on to the review. */
    val done: Boolean = false,
)

/** State holder for the "adjust page" screen: the crop outline of one page. */
@HiltViewModel
internal class CropViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val session: ScanSession,
    private val scanRepository: ScanRepository,
    private val pageFinisher: PageFinisher,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val pageId: String = savedStateHandle.toRoute<CropDestination>().pageId
    private val detector = DocumentDetector()

    /** The untouched capture, kept for the final full-resolution render. */
    private var captureJpeg: ByteArray? = null

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
        mutableState.update { it.copy(outline = page.outline) }
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
            mutableState.update { it.copy(preview = photo, isLoading = false) }
        }
    }

    /** A corner handle was dragged to [position] (fractions of the photo). */
    fun onCornerMoved(index: Int, position: Point2) {
        mutableState.update { current ->
            val outline = current.outline ?: return@update current
            val clamped = Point2(position.x.coerceIn(0.0, 1.0), position.y.coerceIn(0.0, 1.0))
            val moved = outline.withCorner(index, clamped)
            // A corner dragged past its neighbours would fold the page over itself.
            if (moved.isConvex() && moved.area() > MIN_OUTLINE_AREA) {
                current.copy(outline = moved, problem = current.problem.takeUnless { it == CropProblem.NOTHING_DETECTED })
            } else {
                current
            }
        }
    }

    /** Moves a corner by a small step; used by the accessibility actions on each handle. */
    fun onCornerNudged(index: Int, dx: Double, dy: Double) {
        val corner = mutableState.value.outline?.corners?.getOrNull(index) ?: return
        onCornerMoved(index, Point2(corner.x + dx, corner.y + dy))
    }

    /** Looks for the page edges again, for when the corners were moved off course. */
    fun onAutoDetect() {
        val photo = mutableState.value.preview ?: return
        if (mutableState.value.isDetecting) return
        mutableState.update { it.copy(isDetecting = true, problem = null) }
        viewModelScope.launch {
            val detected = withContext(dispatchers.default) { detector.detect(photo) }
                ?: session.page(pageId)?.detectedOutline
            mutableState.update {
                if (detected == null) {
                    it.copy(isDetecting = false, problem = CropProblem.NOTHING_DETECTED)
                } else {
                    it.copy(isDetecting = false, outline = detected)
                }
            }
        }
    }

    fun onUseWholePhoto() {
        mutableState.update { it.copy(outline = Quad.inset(1.0, 1.0, 0.0), problem = null) }
    }

    fun onConfirm() {
        val current = mutableState.value
        val outline = current.outline
        val jpeg = captureJpeg
        val page = session.page(pageId)
        if (current.isSaving || outline == null || jpeg == null || page == null) return
        mutableState.update { it.copy(isSaving = true, problem = null) }
        viewModelScope.launch {
            val output = withContext(dispatchers.default) {
                pageFinisher.finish(pageId, jpeg, outline, page.filter)
            }
            if (output == null) {
                mutableState.update { it.copy(isSaving = false, problem = CropProblem.SAVE_FAILED) }
                return@launch
            }
            session.update(pageId) { it.copy(outline = outline, finished = output.page) }
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

    private companion object {
        const val DISPLAY_LONG_EDGE = 1280
        const val MIN_OUTLINE_AREA = 0.01
    }
}
