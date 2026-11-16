package app.scanova.feature.scan.importer

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.data.settings.AppSettings
import app.scanova.core.data.settings.SettingsRepository
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.imaging.DocumentDetector
import app.scanova.feature.scan.intake.PageIntake
import app.scanova.feature.scan.navigation.PhotoImportDestination
import app.scanova.feature.scan.session.CameraMode
import app.scanova.feature.scan.session.DraftPage
import app.scanova.feature.scan.session.ScanSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface PhotoImportStage {
    /** The photo picker is (about to be) open. */
    data object Picking : PhotoImportStage

    /** Preparing photo [done] + 1 of [total]; [preview] is the latest page added. */
    data class Working(val done: Int, val total: Int, val preview: Bitmap?) : PhotoImportStage

    /** Some photos are in the scan, some could not be used: shown before the review. */
    data class Partial(val outcome: PhotoImportOutcome.SomeAdded) : PhotoImportStage

    /** None of the picked photos could be used. */
    data class Failed(val outcome: PhotoImportOutcome.NoneAdded) : PhotoImportStage

    /** No app can pick photos (should not happen: the documents picker is the fallback). */
    data object PickerUnavailable : PhotoImportStage
}

internal data class PhotoImportUiState(
    val stage: PhotoImportStage = PhotoImportStage.Picking,
    /** The picker should be opened now (once). */
    val launchPicker: Boolean = false,
    val exit: PhotoImportExit? = null,
)

/**
 * Imports photos into the scan in progress, each handled exactly like a
 * camera capture ([PageIntake]): the picked file is kept as the page's
 * original, an upright working copy is stored, the page is found in it
 * (the whole photo when no page is found), and the page joins the scan with
 * the default look and is rendered in the background. The review follows,
 * where every page can be adjusted.
 *
 * Photos are handled one at a time, so only one is ever in memory. Stopping
 * removes what this import added; pages that were in the scan before stay.
 */
@HiltViewModel
internal class PhotoImportViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val session: ScanSession,
    private val intake: PageIntake,
    private val files: PhotoFiles,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    /** Photos handed over by another screen (Tools → Import files); empty: the user picks them here. */
    private val handedOver: List<String> = savedStateHandle.toRoute<PhotoImportDestination>().pictureUris

    private val detector = DocumentDetector()

    private val mutableState = MutableStateFlow(
        PhotoImportUiState(launchPicker = handedOver.isEmpty() && savedStateHandle.get<Boolean>(KEY_PICKER_SHOWN) != true),
    )
    val state: StateFlow<PhotoImportUiState> = mutableState.asStateFlow()

    private var job: Job? = null

    init {
        if (handedOver.isNotEmpty() && savedStateHandle.get<Boolean>(KEY_HANDOVER_STARTED) != true) {
            savedStateHandle[KEY_HANDOVER_STARTED] = true
            start(handedOver)
        }
    }

    /** The picker is open; after a process restart its result still arrives, so it is not opened again. */
    fun onPickerLaunched() {
        savedStateHandle[KEY_PICKER_SHOWN] = true
        mutableState.update { it.copy(launchPicker = false) }
    }

    fun onPickerUnavailable() {
        mutableState.update { it.copy(launchPicker = false, stage = PhotoImportStage.PickerUnavailable) }
    }

    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            // Backed out of the picker: back to where the import was started from.
            if (state.value.stage == PhotoImportStage.Picking) leave()
            return
        }
        start(uris.map(Uri::toString))
    }

    /** "Choose photos" again (after a failure, or when the picker was closed by the system). */
    fun onPickAgain() {
        if (job?.isActive == true) return
        savedStateHandle[KEY_PICKER_SHOWN] = false
        mutableState.update { it.copy(stage = PhotoImportStage.Picking, launchPicker = true) }
    }

    /** "Review pages" after some photos could not be used. */
    fun onContinue() = leave()

    /**
     * Back: while working, stops the import and removes what it added; at any
     * other time leaves for the review (or out of the scan when it has no pages).
     */
    fun onBack() {
        val running = job
        if (running?.isActive == true) {
            running.cancel()
            viewModelScope.launch {
                running.join()
                leave()
            }
        } else {
            leave()
        }
    }

    fun onExitHandled() = mutableState.update { it.copy(exit = null) }

    private fun leave() = mutableState.update { it.copy(exit = PhotoImportExit.leaving(session.pages.value.size)) }

    // ── Importing ───────────────────────────────────────────────────────

    private fun start(picked: List<String>) {
        if (job?.isActive == true) return
        val plan = PhotoImportPlan.of(picked)
        if (plan.photos.isEmpty()) return
        mutableState.update { it.copy(stage = PhotoImportStage.Working(0, plan.photos.size, preview = null), launchPicker = false) }
        job = viewModelScope.launch { run(plan) }
    }

    private suspend fun run(plan: PhotoImportPlan) {
        val settings = settingsRepository.settings
            .catch { emit(AppSettings()) }
            .first()
        // A retake waits for the camera; photos are always added as new pages.
        session.replaceTargetId = null
        val added = mutableListOf<String>()
        val problems = mutableListOf<PhotoProblem>()
        var preview: Bitmap? = null
        try {
            for ((index, uri) in plan.photos.withIndex()) {
                mutableState.update { it.copy(stage = PhotoImportStage.Working(index, plan.photos.size, preview)) }
                when (val step = importOne(uri.toUri(), settings)) {
                    is Step.Added -> {
                        added += step.pageId
                        preview = step.preview
                    }
                    is Step.Skipped -> {
                        problems += step.problem
                        // A full phone fails every photo after this one too.
                        if (step.problem.kind == PhotoProblemKind.STORAGE) break
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { added.forEach { intake.remove(it) } }
            throw e
        }
        mutableState.update { it.copy(stage = PhotoImportStage.Working(plan.photos.size, plan.photos.size, preview)) }
        // Photos not tried after a storage failure could not have been stored either.
        repeat(plan.photos.size - added.size - problems.size) { problems += PhotoProblem(null, PhotoProblemKind.STORAGE) }
        val outcome = PhotoImportOutcome.of(added.size, problems, overLimit = plan.overLimit)
        mutableState.update {
            when (outcome) {
                PhotoImportOutcome.AllAdded -> it.copy(exit = PhotoImportExit.REVIEW)
                is PhotoImportOutcome.SomeAdded -> it.copy(stage = PhotoImportStage.Partial(outcome))
                is PhotoImportOutcome.NoneAdded -> it.copy(stage = PhotoImportStage.Failed(outcome))
            }
        }
    }

    private sealed interface Step {
        class Added(val pageId: String, val preview: Bitmap) : Step

        class Skipped(val problem: PhotoProblem) : Step
    }

    /** One photo into the scan, like a camera capture; never more than this one photo in memory. */
    private suspend fun importOne(uri: Uri, settings: AppSettings): Step {
        val file = when (val read = files.read(uri)) {
            is PhotoFile.Loaded -> read
            is PhotoFile.Unavailable -> return Step.Skipped(PhotoProblem(read.name, read.kind))
        }
        val pageId = intake.newPageId()
        var addedToScan = false
        try {
            return withContext(dispatchers.default) {
                val prepared = PhotoPreparer.prepare(file.bytes)
                    ?: return@withContext Step.Skipped(PhotoProblem(file.name, PhotoProblemKind.UNREADABLE))
                val upright = prepared.upright
                try {
                    // No page found: the whole photo (a screenshot, a scan from another app).
                    val outline = detector.detect(upright)
                    val working = Bitmaps.toJpeg(upright, PageIntake.WORKING_JPEG_QUALITY)
                    if (!intake.store(pageId, original = prepared.original, working = working)) {
                        return@withContext Step.Skipped(PhotoProblem(file.name, PhotoProblemKind.STORAGE))
                    }
                    val preview = intake.quickPreview(upright, outline)
                    intake.add(
                        DraftPage(
                            id = pageId,
                            mode = CameraMode.DOCUMENT,
                            outline = outline,
                            detectedOutline = outline,
                            look = intake.newPageLook(settings),
                            sourceRotation = prepared.sourceRotation,
                            captureWidth = upright.width,
                            captureHeight = upright.height,
                            quickThumbnail = preview,
                            finished = null,
                        ),
                    )
                    addedToScan = true
                    Step.Added(pageId, preview)
                } finally {
                    upright.recycle()
                }
            }
        } catch (e: OutOfMemoryError) {
            discardUnless(addedToScan, pageId)
            return Step.Skipped(PhotoProblem(file.name, PhotoProblemKind.TOO_LARGE))
        } catch (e: CancellationException) {
            discardUnless(addedToScan, pageId)
            throw e
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "photo_import_failed", e)
            discardUnless(addedToScan, pageId)
            return Step.Skipped(PhotoProblem(file.name, PhotoProblemKind.UNREADABLE))
        }
    }

    /** Files of a page that did not make it into the scan are deleted; a page in the scan is removed by the caller. */
    private suspend fun discardUnless(addedToScan: Boolean, pageId: String) {
        if (!addedToScan) withContext(NonCancellable) { intake.discard(pageId) }
    }

    override fun onCleared() {
        job?.cancel()
    }

    private companion object {
        const val TAG = "PhotoImport"
        const val KEY_PICKER_SHOWN = "picker_shown"
        const val KEY_HANDOVER_STARTED = "handover_started"
    }
}
