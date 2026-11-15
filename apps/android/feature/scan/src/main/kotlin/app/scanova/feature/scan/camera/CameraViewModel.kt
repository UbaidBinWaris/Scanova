package app.scanova.feature.scan.camera

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.scan.ScanRepository
import app.scanova.core.data.settings.SettingsRepository
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.geometry.QuadSmoother
import app.scanova.feature.scan.geometry.SteadinessTracker
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.imaging.DocumentDetector
import app.scanova.feature.scan.session.DraftPage
import app.scanova.feature.scan.session.ScanFilter
import app.scanova.feature.scan.session.ScanSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CameraProblem { CAMERA_UNAVAILABLE, CAPTURE_FAILED, STORAGE_FAILED }

data class CameraUiState(
    /** Page outline to draw over the preview, as fractions of the upright frame. */
    val outline: Quad? = null,
    /** Width divided by height of the upright camera frame. */
    val frameAspect: Float = 3f / 4f,
    val autoCapture: Boolean = true,
    /** 0..1: how long the page has been held still, for the ring around the shutter. */
    val steadyProgress: Float = 0f,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val isCapturing: Boolean = false,
    /** Pages already in this scan. */
    val pageCount: Int = 0,
    /** Small image of the most recent finished page, for the page stack next to the shutter. */
    val lastThumbnail: Bitmap? = null,
    /** The next photo replaces an existing page. */
    val isRetake: Boolean = false,
    val problem: CameraProblem? = null,
    /** Bumped by "try again" after the camera failed to start: the screen opens a new camera session. */
    val cameraSession: Int = 0,
    /** Set when a photo was stored: the screen navigates to cropping this page. */
    val capturedPageId: String? = null,
)

/** State holder for the camera screen. */
@HiltViewModel
internal class CameraViewModel @Inject constructor(
    private val session: ScanSession,
    private val scanRepository: ScanRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    val detector = DocumentDetector()
    private val steadiness = SteadinessTracker()
    private val steadinessLock = Any()

    private val mutableState = MutableStateFlow(CameraUiState(isRetake = session.replaceTargetId != null))
    val state: StateFlow<CameraUiState> = mutableState.asStateFlow()

    /**
     * "Take the picture now", from the shutter button or from auto-capture. An
     * event rather than state: it must be acted on once, and never again when
     * the screen is shown a second time.
     */
    private val captureEvents = Channel<Unit>(Channel.CONFLATED)
    val captureRequests: Flow<Unit> = captureEvents.receiveAsFlow()

    /** A photo is being decoded and stored. Only touched on the main thread. */
    private var processingPhoto = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                mutableState.update { it.copy(autoCapture = settings.autoCaptureEnabled) }
            }
        }
        viewModelScope.launch {
            session.pages.collect { pages ->
                val lastThumbnail = pages.lastOrNull { it.finished != null }?.finished?.thumbnail
                mutableState.update { it.copy(pageCount = pages.size, lastThumbnail = lastThumbnail) }
            }
        }
    }

    /** Called from the camera's analysis thread for every analysed frame. */
    fun onFrame(result: FrameResult) {
        val reading = synchronized(steadinessLock) { steadiness.onFrame(result.outline, result.timeMillis) }
        var capture = false
        mutableState.update { current ->
            val outline = result.outline?.let { QuadSmoother.blend(current.outline, it) }
            capture = current.autoCapture && reading.shouldCapture && !current.isCapturing && current.capturedPageId == null
            current.copy(
                outline = outline,
                frameAspect = result.uprightAspect,
                steadyProgress = if (current.autoCapture) reading.progress else 0f,
                isCapturing = current.isCapturing || capture,
            )
        }
        if (capture) captureEvents.trySend(Unit)
    }

    /** The camera screen (re)appeared: forget requests that were never carried out. */
    fun onScreenShown() {
        while (captureEvents.tryReceive().isSuccess) {
            // Drain: a request made before the screen went away is stale.
        }
        synchronized(steadinessLock) { steadiness.reset() }
        if (!processingPhoto) mutableState.update { it.copy(isCapturing = false) }
    }

    fun onCameraReady(hasTorch: Boolean) {
        mutableState.update { it.copy(torchAvailable = hasTorch, problem = null) }
    }

    fun onCameraError() {
        mutableState.update { it.copy(problem = CameraProblem.CAMERA_UNAVAILABLE, torchOn = false) }
    }

    /** "Try again" after the camera could not be started. */
    fun onRetryCamera() {
        synchronized(steadinessLock) { steadiness.reset() }
        mutableState.update {
            it.copy(problem = null, outline = null, steadyProgress = 0f, cameraSession = it.cameraSession + 1)
        }
    }

    fun onShutterPressed() {
        var started = false
        mutableState.update { current ->
            started = !current.isCapturing &&
                current.capturedPageId == null &&
                current.problem != CameraProblem.CAMERA_UNAVAILABLE
            if (started) current.copy(isCapturing = true, problem = null) else current
        }
        if (started) captureEvents.trySend(Unit)
    }

    fun onToggleTorch() {
        mutableState.update { it.copy(torchOn = !it.torchOn) }
    }

    fun onToggleAutoCapture() {
        val enabled = !mutableState.value.autoCapture
        mutableState.update { it.copy(autoCapture = enabled, steadyProgress = 0f) }
        viewModelScope.launch { settingsRepository.setAutoCaptureEnabled(enabled) }
    }

    /** The camera delivered a photo (or null when the capture failed). */
    fun onPhoto(photo: CapturedPhoto?) {
        if (photo == null) {
            synchronized(steadinessLock) { steadiness.reset() }
            mutableState.update { it.copy(isCapturing = false, problem = CameraProblem.CAPTURE_FAILED) }
            return
        }
        val liveOutline = mutableState.value.outline
        processingPhoto = true
        viewModelScope.launch {
            val pageId = try {
                withContext(dispatchers.default) { storeCapture(photo, liveOutline) }
            } finally {
                processingPhoto = false
            }
            synchronized(steadinessLock) { steadiness.reset() }
            mutableState.update {
                if (pageId == null) {
                    it.copy(isCapturing = false, problem = CameraProblem.STORAGE_FAILED)
                } else {
                    it.copy(isCapturing = false, capturedPageId = pageId, torchOn = false)
                }
            }
        }
    }

    /** The screen has navigated to the crop screen for the captured page. */
    fun onCapturedPageHandled() {
        mutableState.update { it.copy(capturedPageId = null, isRetake = false) }
    }

    fun onProblemShown() {
        mutableState.update { it.copy(problem = null) }
    }

    /** Leaving the camera without taking the retake photo keeps the existing page. */
    fun onLeaving() {
        session.replaceTargetId = null
    }

    private suspend fun storeCapture(photo: CapturedPhoto, liveOutline: Quad?): String? {
        val decoded = Bitmaps.decodeSampled(photo.jpeg, MAX_CAPTURE_EDGE) ?: return null
        val upright = Bitmaps.rotate(decoded, photo.rotationDegrees)
        try {
            // The photo is sharper than the preview frames, so look again; fall
            // back to what the preview showed, then to the whole picture.
            val detected = detector.detect(upright)
            val outline = detected ?: liveOutline ?: Quad.inset(1.0, 1.0, DEFAULT_INSET)
            val pageId = scanRepository.newPageId()
            val stored = scanRepository.storeCapture(pageId, Bitmaps.toJpeg(upright, CAPTURE_JPEG_QUALITY))
            if (stored is AppResult.Failure) return null

            val previous = session.pages.value.lastOrNull()
            val replaced = session.addOrReplace(
                DraftPage(
                    id = pageId,
                    outline = outline,
                    detectedOutline = detected ?: liveOutline,
                    // Pages of one document usually want the same look.
                    filter = previous?.filter ?: ScanFilter.ENHANCED,
                    captureWidth = upright.width,
                    captureHeight = upright.height,
                    finished = null,
                ),
            )
            replaced?.let { scanRepository.discardPage(it) }
            return pageId
        } finally {
            upright.recycle()
        }
    }

    private companion object {
        /** Longest side kept from a photo: enough for a sharp A4 page at about 220 dpi. */
        const val MAX_CAPTURE_EDGE = 2560
        const val CAPTURE_JPEG_QUALITY = 92
        const val DEFAULT_INSET = 0.06
    }
}
