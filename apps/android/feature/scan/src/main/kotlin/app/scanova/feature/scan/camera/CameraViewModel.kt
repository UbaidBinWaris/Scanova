package app.scanova.feature.scan.camera

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageFilters
import app.scanova.core.data.scan.ScanRepository
import app.scanova.core.data.settings.AppSettings
import app.scanova.core.data.settings.FlashMode
import app.scanova.core.data.settings.SettingsRepository
import app.scanova.feature.scan.filter.FilterCatalogue
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.geometry.QuadSmoother
import app.scanova.feature.scan.geometry.SteadinessTracker
import app.scanova.feature.scan.idcard.IdCardComposer
import app.scanova.feature.scan.idcard.IdCardLayout
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.imaging.DocumentDetector
import app.scanova.feature.scan.render.PageLook
import app.scanova.feature.scan.render.PageRecipeRenderer
import app.scanova.feature.scan.render.RecipeGeometry
import app.scanova.feature.scan.session.CameraMode
import app.scanova.feature.scan.session.DraftPage
import app.scanova.feature.scan.session.PageFinisher
import app.scanova.feature.scan.session.PageProcessor
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

internal enum class CameraProblem { CAMERA_UNAVAILABLE, CAPTURE_FAILED, STORAGE_FAILED }

/** Which side of an ID card the next picture is of. */
internal enum class IdSide { FRONT, BACK }

/** A short confirmation shown after a camera setting was changed, or a step of the ID card flow. */
internal enum class CameraNotice {
    FLASH_OFF, FLASH_AUTO, FLASH_ON, TORCH,
    GRID_ON, GRID_OFF,
    HD_ON, HD_OFF,
    AUTO_CAPTURE_ON, AUTO_CAPTURE_OFF,
    AUTO_ENHANCE_ON, AUTO_ENHANCE_OFF,
    TURN_CARD,
}

/** A page on its way into the page stack: the capture animation. */
internal class FlyingPage(val key: Long, val image: Bitmap)

internal data class CameraUiState(
    /** Page outline to draw over the preview, as fractions of the upright frame. */
    val outline: Quad? = null,
    /** Width divided by height of the upright camera frame. */
    val frameAspect: Float = 3f / 4f,
    val mode: CameraMode = CameraMode.DOCUMENT,
    /** Settings have been read: the camera can start (HD decides its resolution). */
    val settingsLoaded: Boolean = false,
    val autoCapture: Boolean = false,
    val autoEnhance: Boolean = true,
    val flash: FlashMode = FlashMode.OFF,
    val grid: Boolean = false,
    val hd: Boolean = true,
    /** 0..1: how long the page has been held still, for the ring around the shutter. */
    val steadyProgress: Float = 0f,
    /** A reflection on the page: the hint asks to tilt the phone (text under it would be lost). */
    val glare: Boolean = false,
    val flashAvailable: Boolean = false,
    val isCapturing: Boolean = false,
    /** Pages already in this scan. */
    val pageCount: Int = 0,
    /** Small image of the most recent page, for the page stack next to the shutter. */
    val lastThumbnail: Bitmap? = null,
    /** Pictures taken since the camera opened: "undo" removes the latest of them. */
    val capturedThisVisit: Int = 0,
    /** The next photo replaces an existing page. */
    val isRetake: Boolean = false,
    val idSide: IdSide = IdSide.FRONT,
    /** Small image of the card's front once it is taken. */
    val idFront: Bitmap? = null,
    val flying: FlyingPage? = null,
    val notice: CameraNotice? = null,
    /** Bumped with every notice, so the same notice twice is shown twice. */
    val noticeKey: Int = 0,
    val problem: CameraProblem? = null,
    /** Bumped by "try again" after the camera failed to start: the screen opens a new camera session. */
    val cameraSession: Int = 0,
    /** Set when the screen should move on to the review (a retake or an ID card is done). */
    val openReview: Boolean = false,
)

/** State holder for the camera screen. */
@HiltViewModel
internal class CameraViewModel @Inject constructor(
    private val session: ScanSession,
    private val processor: PageProcessor,
    private val scanRepository: ScanRepository,
    private val settingsRepository: SettingsRepository,
    private val renderer: PageRecipeRenderer,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    val detector = DocumentDetector()
    private val steadiness = SteadinessTracker()

    /** Guards [steadiness] and the auto-capture arming below (analysis thread and main thread). */
    private val steadinessLock = Any()

    /**
     * After a picture, auto-capture waits until the page has left the frame or
     * moved clearly: otherwise it would photograph the same page again.
     */
    private var autoArmed = true
    private var lastCapturedOutline: Quad? = null
    private var framesWithoutPage = 0

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

    /** Pages taken since the camera opened, oldest first (main thread). */
    private val capturedIds = ArrayList<String>()

    /** The flattened front of the ID card, waiting for the back (main thread). */
    private var idFrontCard: Bitmap? = null

    @Volatile
    private var settings = AppSettings()
    private var started = false
    private var flyingKey = 0L

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { latest ->
                settings = latest
                mutableState.update {
                    it.copy(
                        settingsLoaded = true,
                        autoCapture = latest.autoCaptureEnabled,
                        autoEnhance = latest.autoEnhance,
                        flash = latest.cameraFlash,
                        grid = latest.cameraGrid,
                        hd = latest.cameraHd,
                    )
                }
            }
        }
        viewModelScope.launch {
            session.pages.collect { pages ->
                mutableState.update { it.copy(pageCount = pages.size, lastThumbnail = pages.lastOrNull()?.thumbnail) }
            }
        }
    }

    /** The screen opened. [idCard]: the scan was started as an ID card scan. */
    fun start(idCard: Boolean) {
        if (started) return
        started = true
        val requested = if (idCard) CameraMode.ID_CARD else CameraMode.DOCUMENT
        val retaking = session.replaceTargetId?.let(session::page)
        // A retake uses the page's own mode; adding pages to a scan, the mode last used; a new scan, the one asked for.
        val mode = when {
            retaking != null -> retaking.mode
            session.pages.value.isEmpty() -> requested
            else -> session.lastMode ?: requested
        }
        session.lastMode = mode
        mutableState.update { it.copy(mode = mode) }
    }

    /** Called from the camera's analysis thread for every analysed frame. */
    fun onFrame(result: FrameResult) {
        val current = mutableState.value
        // In ID card mode only an outline that sits in the card guide counts.
        val seen = if (current.mode == CameraMode.ID_CARD) {
            result.outline?.takeIf { IdCardLayout.matchesGuide(it, IdCardLayout.guide(result.uprightAspect.toDouble())) }
        } else {
            result.outline
        }
        var armed = true
        val reading = synchronized(steadinessLock) {
            updateArming(seen)
            armed = autoArmed
            steadiness.onFrame(seen, result.timeMillis)
        }
        var capture = false
        mutableState.update { state ->
            val outline = seen?.let { QuadSmoother.blend(state.outline, it) }
            val automatic = state.autoCapture && armed
            // Never photograph a reflection on its own: the text under it would be lost.
            capture = automatic && reading.shouldCapture && !result.glare && !state.isCapturing &&
                state.problem != CameraProblem.CAMERA_UNAVAILABLE && !state.openReview
            state.copy(
                outline = outline,
                frameAspect = result.uprightAspect,
                steadyProgress = if (automatic) reading.progress else 0f,
                glare = result.glare && seen != null,
                isCapturing = state.isCapturing || capture,
            )
        }
        if (capture) captureEvents.trySend(Unit)
    }

    private fun updateArming(outline: Quad?) {
        if (autoArmed) return
        if (outline == null) {
            framesWithoutPage++
            if (framesWithoutPage > REARM_MISSING_FRAMES) autoArmed = true
            return
        }
        framesWithoutPage = 0
        val last = lastCapturedOutline
        if (last == null || last.maxCornerDistance(outline) > REARM_DISTANCE) autoArmed = true
    }

    /** The camera screen (re)appeared: forget requests that were never carried out. */
    fun onScreenShown() {
        while (captureEvents.tryReceive().isSuccess) {
            // Drain: a request made before the screen went away is stale.
        }
        synchronized(steadinessLock) { steadiness.reset() }
        if (!processingPhoto) mutableState.update { it.copy(isCapturing = false) }
    }

    fun onCameraReady(hasFlash: Boolean) {
        mutableState.update { it.copy(flashAvailable = hasFlash, problem = null) }
    }

    fun onCameraError() {
        mutableState.update { it.copy(problem = CameraProblem.CAMERA_UNAVAILABLE) }
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
                !current.openReview &&
                current.problem != CameraProblem.CAMERA_UNAVAILABLE
            if (started) current.copy(isCapturing = true, problem = null) else current
        }
        if (started) captureEvents.trySend(Unit)
    }

    // ── Settings in the top bar ─────────────────────────────────────────

    /** Off → auto → on → torch → off. */
    fun onFlashPressed() {
        val next = when (mutableState.value.flash) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        val notice = when (next) {
            FlashMode.OFF -> CameraNotice.FLASH_OFF
            FlashMode.AUTO -> CameraNotice.FLASH_AUTO
            FlashMode.ON -> CameraNotice.FLASH_ON
            FlashMode.TORCH -> CameraNotice.TORCH
        }
        mutableState.update { it.copy(flash = next).withNotice(notice) }
        viewModelScope.launch { settingsRepository.setCameraFlash(next) }
    }

    fun onGridToggled() {
        val enabled = !mutableState.value.grid
        mutableState.update { it.copy(grid = enabled).withNotice(if (enabled) CameraNotice.GRID_ON else CameraNotice.GRID_OFF) }
        viewModelScope.launch { settingsRepository.setCameraGrid(enabled) }
    }

    /** HD changes the capture resolution: the screen restarts the camera with the new setting. */
    fun onHdToggled() {
        if (mutableState.value.isCapturing) return
        val enabled = !mutableState.value.hd
        mutableState.update { it.copy(hd = enabled).withNotice(if (enabled) CameraNotice.HD_ON else CameraNotice.HD_OFF) }
        viewModelScope.launch { settingsRepository.setCameraHd(enabled) }
    }

    fun onAutoCaptureToggled() {
        val enabled = !mutableState.value.autoCapture
        synchronized(steadinessLock) { steadiness.reset() }
        mutableState.update {
            it.copy(autoCapture = enabled, steadyProgress = 0f)
                .withNotice(if (enabled) CameraNotice.AUTO_CAPTURE_ON else CameraNotice.AUTO_CAPTURE_OFF)
        }
        viewModelScope.launch { settingsRepository.setAutoCaptureEnabled(enabled) }
    }

    fun onAutoEnhanceToggled() {
        val enabled = !mutableState.value.autoEnhance
        mutableState.update {
            it.copy(autoEnhance = enabled).withNotice(if (enabled) CameraNotice.AUTO_ENHANCE_ON else CameraNotice.AUTO_ENHANCE_OFF)
        }
        settings = settings.copy(autoEnhance = enabled)
        viewModelScope.launch { settingsRepository.setAutoEnhance(enabled) }
    }

    fun onNoticeShown(key: Int) {
        mutableState.update { if (it.noticeKey == key) it.copy(notice = null) else it }
    }

    // ── Modes, undo ─────────────────────────────────────────────────────

    fun onModeSelected(mode: CameraMode) {
        val current = mutableState.value
        if (current.mode == mode || current.isCapturing || current.isRetake) return
        session.lastMode = mode
        resetIdCard()
        synchronized(steadinessLock) { steadiness.reset() }
        mutableState.update { it.copy(mode = mode, outline = null, steadyProgress = 0f, notice = null) }
    }

    /** Takes back the last picture: the card's front while waiting for its back, otherwise the last page taken here. */
    fun onUndo() {
        val current = mutableState.value
        if (current.isCapturing) return
        if (current.mode == CameraMode.ID_CARD && current.idSide == IdSide.BACK) {
            resetIdCard()
            return
        }
        val pageId = capturedIds.removeLastOrNull() ?: return
        session.remove(pageId)
        mutableState.update { it.copy(capturedThisVisit = capturedIds.size) }
        viewModelScope.launch { scanRepository.discardPage(pageId) }
    }

    private fun resetIdCard() {
        idFrontCard?.recycle()
        idFrontCard = null
        mutableState.update { it.copy(idSide = IdSide.FRONT, idFront = null) }
    }

    fun onFlyingPageShown(key: Long) {
        mutableState.update { if (it.flying?.key == key) it.copy(flying = null) else it }
    }

    /** The screen has moved on to the review. */
    fun onReviewOpened() {
        mutableState.update { it.copy(openReview = false) }
    }

    fun onProblemShown() {
        mutableState.update { it.copy(problem = null) }
    }

    /** Leaving the camera without taking the retake photo keeps the existing page. */
    fun onLeaving() {
        session.replaceTargetId = null
    }

    // ── Pictures ────────────────────────────────────────────────────────

    /** The camera delivered a photo (or null when the capture failed). */
    fun onPhoto(photo: CapturedPhoto?) {
        if (photo == null) {
            synchronized(steadinessLock) { steadiness.reset() }
            mutableState.update { it.copy(isCapturing = false, problem = CameraProblem.CAPTURE_FAILED) }
            return
        }
        val current = mutableState.value
        val liveOutline = current.outline
        synchronized(steadinessLock) {
            autoArmed = false
            lastCapturedOutline = liveOutline
            framesWithoutPage = 0
            steadiness.reset()
        }
        processingPhoto = true
        val front = idFrontCard
        viewModelScope.launch {
            val outcome = try {
                withContext(dispatchers.default) {
                    try {
                        when {
                            current.mode == CameraMode.DOCUMENT -> storeDocumentPage(photo, liveOutline)
                            front == null -> captureCardSide(photo, liveOutline)?.let { Outcome.FrontTaken(it) }
                            else -> storeIdCardPage(photo, liveOutline, front)
                        }
                    } catch (e: OutOfMemoryError) {
                        null
                    }
                }
            } finally {
                processingPhoto = false
            }
            onOutcome(outcome, current)
        }
    }

    private fun onOutcome(outcome: Outcome?, before: CameraUiState) {
        when (outcome) {
            null -> mutableState.update { it.copy(isCapturing = false, problem = CameraProblem.STORAGE_FAILED) }
            is Outcome.FrontTaken -> {
                idFrontCard?.recycle()
                idFrontCard = outcome.card
                mutableState.update {
                    it.copy(isCapturing = false, idSide = IdSide.BACK, idFront = outcome.thumbnail, outline = null)
                        .withNotice(CameraNotice.TURN_CARD)
                }
            }
            is Outcome.PageAdded -> {
                if (before.mode == CameraMode.ID_CARD) {
                    idFrontCard?.recycle()
                    idFrontCard = null
                }
                capturedIds += outcome.pageId
                val leave = before.isRetake || before.mode == CameraMode.ID_CARD
                mutableState.update {
                    it.copy(
                        isCapturing = false,
                        isRetake = false,
                        capturedThisVisit = capturedIds.size,
                        idSide = IdSide.FRONT,
                        idFront = null,
                        openReview = leave,
                        flying = if (leave) it.flying else FlyingPage(++flyingKey, outcome.preview),
                    )
                }
            }
        }
    }

    private sealed interface Outcome {
        class PageAdded(val pageId: String, val preview: Bitmap) : Outcome

        class FrontTaken(val card: Bitmap, val thumbnail: Bitmap) : Outcome {
            constructor(card: Bitmap) : this(card, Bitmaps.thumbnail(card, SIDE_THUMBNAIL_LONG_EDGE))
        }
    }

    /** Stores the raw photo as the page's original, an upright working copy, and adds the page. */
    private suspend fun storeDocumentPage(photo: CapturedPhoto, liveOutline: Quad?): Outcome? {
        val decoded = Bitmaps.decodeWithin(photo.jpeg, PageFinisher.CAPTURE_LONG_EDGE, MIN_CAPTURE_LONG_EDGE) ?: return null
        val upright = Bitmaps.rotate(decoded, photo.rotationDegrees)
        try {
            // The photo is sharper than the preview frames, so look again; fall
            // back to what the preview showed, then to most of the picture.
            val detected = detector.detect(upright)
            val outline = detected ?: liveOutline ?: Quad.inset(1.0, 1.0, DEFAULT_INSET)
            val pageId = scanRepository.newPageId()
            if (!store(pageId, original = photo.jpeg, working = Bitmaps.toJpeg(upright, CAPTURE_JPEG_QUALITY))) return null
            val preview = quickPreview(upright, outline)
            val replacing = session.replaceTargetId?.let(session::page)
            val page = DraftPage(
                id = pageId,
                mode = CameraMode.DOCUMENT,
                outline = outline,
                detectedOutline = detected ?: liveOutline,
                look = replacing?.look ?: newPageLook(),
                sourceRotation = RecipeGeometry.normalizeDegrees(photo.rotationDegrees),
                captureWidth = upright.width,
                captureHeight = upright.height,
                quickThumbnail = preview,
                finished = null,
            )
            addPage(page)
            return Outcome.PageAdded(pageId, preview)
        } finally {
            upright.recycle()
        }
    }

    /** One side of an ID card, flattened: the card found in the guide, or the guide's area. */
    private fun captureCardSide(photo: CapturedPhoto, liveOutline: Quad?): Bitmap? {
        val decoded = Bitmaps.decodeWithin(photo.jpeg, PageFinisher.CAPTURE_LONG_EDGE, MIN_CAPTURE_LONG_EDGE) ?: return null
        val upright = Bitmaps.rotate(decoded, photo.rotationDegrees)
        try {
            val guide = IdCardLayout.guide(upright.width.toDouble() / upright.height)
            val outline = detector.detect(upright)?.takeIf { IdCardLayout.matchesGuide(it, guide) }
                ?: liveOutline?.takeIf { IdCardLayout.matchesGuide(it, guide) }
                ?: guide
            return renderer.geometry(upright, PageEdit(crop = RecipeGeometry.toCrop(outline)), IdCardComposer.SIDE_LONG_EDGE)
        } finally {
            upright.recycle()
        }
    }

    /**
     * Both sides on one A4 page. The composed, unfiltered page is the page's
     * original (crop null): the camera photos themselves are not kept.
     */
    private suspend fun storeIdCardPage(photo: CapturedPhoto, liveOutline: Quad?, front: Bitmap): Outcome? {
        val back = captureCardSide(photo, liveOutline) ?: return null
        val composed = try {
            IdCardComposer.compose(front, back)
        } finally {
            back.recycle()
        }
        try {
            val jpeg = Bitmaps.toJpeg(composed, ID_PAGE_JPEG_QUALITY)
            val pageId = scanRepository.newPageId()
            if (!store(pageId, original = jpeg, working = jpeg)) return null
            val preview = Bitmaps.thumbnail(composed, PageFinisher.THUMBNAIL_LONG_EDGE)
            val replacing = session.replaceTargetId?.let(session::page)
            addPage(
                DraftPage(
                    id = pageId,
                    mode = CameraMode.ID_CARD,
                    outline = null,
                    detectedOutline = null,
                    look = replacing?.look ?: newPageLook(),
                    captureWidth = composed.width,
                    captureHeight = composed.height,
                    quickThumbnail = preview,
                    finished = null,
                ),
            )
            return Outcome.PageAdded(pageId, preview)
        } finally {
            composed.recycle()
        }
    }

    private suspend fun store(pageId: String, original: ByteArray, working: ByteArray): Boolean {
        val stored = scanRepository.storeOriginal(pageId, original) is AppResult.Success &&
            scanRepository.storeCapture(pageId, working) is AppResult.Success
        if (!stored) scanRepository.discardPage(pageId)
        return stored
    }

    private suspend fun addPage(page: DraftPage) {
        val replaced = session.addOrReplace(page)
        replaced?.let { scanRepository.discardPage(it) }
        processor.enqueue(page.id)
    }

    /** A small flattened, unfiltered image of the page for the capture animation and the page stack. */
    private fun quickPreview(upright: Bitmap, outline: Quad): Bitmap {
        val small = Bitmaps.thumbnail(upright, QUICK_SOURCE_LONG_EDGE)
        try {
            return renderer.geometry(small, PageEdit(crop = RecipeGeometry.toCrop(outline)), QUICK_PREVIEW_LONG_EDGE)
        } finally {
            small.recycle()
        }
    }

    /**
     * The look a new page starts with: with auto-enhance on, the look of the
     * previous page (pages of one document usually want the same) or the
     * default filter; with it off, the original.
     */
    private fun newPageLook(): PageLook {
        if (!settings.autoEnhance) return PageLook(PageFilters.ORIGINAL)
        val previous = session.pages.value.lastOrNull()?.look
        if (previous != null && previous.filterId != PageFilters.ORIGINAL) return previous
        val default = settings.defaultFilter.takeIf { FilterCatalogue.isKnown(it) && it != PageFilters.ORIGINAL }
        return PageLook(default ?: PageFilters.ENHANCED)
    }

    private fun CameraUiState.withNotice(notice: CameraNotice) = copy(notice = notice, noticeKey = noticeKey + 1)

    private companion object {
        const val MIN_CAPTURE_LONG_EDGE = 1200
        const val CAPTURE_JPEG_QUALITY = 92
        const val ID_PAGE_JPEG_QUALITY = 92
        const val DEFAULT_INSET = 0.06
        const val QUICK_SOURCE_LONG_EDGE = 720
        const val QUICK_PREVIEW_LONG_EDGE = 360
        const val SIDE_THUMBNAIL_LONG_EDGE = 240

        /** Frames without a page after which auto-capture is armed again. */
        const val REARM_MISSING_FRAMES = 3

        /** Corner movement (fraction of the frame) after which auto-capture is armed again: a new page. */
        const val REARM_DISTANCE = 0.12
    }
}
