package app.scanova.feature.scan.session

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.scan.ScanRepository
import app.scanova.feature.scan.cleanup.PageCleaner
import app.scanova.feature.scan.filter.ServerCleanup
import app.scanova.feature.scan.render.geometryKey
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Renders the pages of the scan in progress in the background, one at a time,
 * whatever screen is showing: the camera keeps taking pictures while earlier
 * pages are flattened and filtered, and the review screen's changes are
 * rendered while the user keeps editing.
 *
 * A page is rendered from its *current* draft; if the draft changes while it
 * is being rendered, it is rendered again, so the stored image always ends up
 * matching the latest choice ([DraftPage.isUpToDate]).
 *
 * Two answers may be needed before a page renders, each kept for the geometry
 * it was asked about: the flattening model's mesh, and for the Server
 * clean-up look the page cleaned by the server ([PageCleaner]). A clean-up
 * that fails leaves the page with the stand-in look
 * ([FinishedPage.cleanupMissing]); it is tried again on the page's next
 * change, or when Server clean-up is chosen again ([enqueue] with `retryCleanup`).
 */
@ActivityRetainedScoped
internal class PageProcessor @Inject constructor(
    private val session: ScanSession,
    private val scanRepository: ScanRepository,
    private val finisher: PageFinisher,
    private val flattener: CurlExaminer,
    private val cleaner: PageCleaner,
    dispatchers: AppDispatchers,
    lifecycle: ActivityRetainedLifecycle,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val queue = Channel<String>(Channel.UNLIMITED)

    private val mutablePending = MutableStateFlow<Set<String>>(emptySet())

    /** Pages waiting to be rendered or being rendered. */
    val pending: StateFlow<Set<String>> = mutablePending.asStateFlow()

    private val mutableFailed = MutableStateFlow<Set<String>>(emptySet())

    /** Pages whose last render failed (storage full, image unreadable). Cleared when the page is queued again. */
    val failed: StateFlow<Set<String>> = mutableFailed.asStateFlow()

    /**
     * Pages whose orientation has been decided by text recognition (turned
     * upright, or found upright). Decided once per page, on its first render;
     * only the worker coroutine touches it.
     */
    private val orientationDecided = HashSet<String>()

    /** Pages whose failed clean-up is to be tried again although nothing else changed (read once by the worker). */
    private val cleanupRetries: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        lifecycle.addOnClearedListener { scope.cancel() }
        scope.launch {
            for (pageId in queue) process(pageId)
        }
    }

    /**
     * Renders page [pageId] (again) from its current draft. With
     * [retryCleanup] (Server clean-up chosen again), a page left with the
     * stand-in look by a failed clean-up is cleaned and rendered again too.
     */
    fun enqueue(pageId: String, retryCleanup: Boolean = false) {
        if (retryCleanup) cleanupRetries += pageId
        mutableFailed.update { it - pageId }
        var added = false
        mutablePending.update { pending ->
            added = pageId !in pending
            pending + pageId
        }
        // Already queued: the worker reads the latest draft when it gets to it.
        if (added) queue.trySend(pageId)
    }

    /** Suspends until every queued page has been rendered (or failed). */
    suspend fun awaitIdle() {
        pending.first { it.isEmpty() }
    }

    private suspend fun process(pageId: String) {
        var failed = false
        try {
            repeat(MAX_ROUNDS) {
                val asked = session.page(pageId) ?: return
                if (!asked.needsRender(retryCleanup = cleanupRetries.remove(pageId))) return
                val capture = when (val loaded = scanRepository.loadCapture(pageId)) {
                    is AppResult.Success -> loaded.value
                    is AppResult.Failure -> {
                        failed = true
                        return
                    }
                }
                if (asked.needsFlattening) {
                    val found = flattener.examine(capture, asked.outline)
                    // Kept only for the outline the model looked at: a crop changed meanwhile asks again.
                    session.update(pageId) { current -> if (current.outline == asked.outline) current.copy(flattening = found) else current }
                }
                val draft = session.page(pageId) ?: return
                if (draft.needsFlattening) return@repeat
                val target = draft.toWorkingEdit()
                val previous = draft.finished
                // Text depends on what is on the page (its geometry, flattening included), not on its look.
                val sameText = previous != null &&
                    previous.recipe.crop == target.crop &&
                    previous.recipe.rotation == target.rotation &&
                    previous.recipe.flatten == target.flatten
                // Which way up the page is gets decided once, on its first render — camera
                // captures and gallery imports alike — and only while the user has not turned
                // it. ID card pages are laid out by the app and always upright.
                val detectTurn = previous == null && pageId !in orientationDecided &&
                    draft.mode == CameraMode.DOCUMENT && target.rotation == 0
                // Server clean-up: the page's cleaned layer for this geometry, asked of the server first when none is kept.
                val wantsCleanup = ServerCleanup.isCleanup(target.filter)
                val cleaned = if (wantsCleanup) {
                    cleaner.layer(pageId, target, PageFinisher.PAGE_LONG_EDGE) { finisher.flat(capture, target) }
                } else {
                    null
                }
                val rendered = try {
                    finisher.finish(
                        pageId,
                        capture,
                        target,
                        knownText = previous?.ocrText?.takeIf { sameText },
                        detectTurn = detectTurn,
                        cleaned = cleaned,
                    )?.also { page ->
                        // Recognition turned the page: its cleaned layer turns with it, so the turned page needs no new clean-up.
                        if (cleaned != null && page.recipe.rotation != target.rotation) {
                            cleaner.keepTurned(pageId, page.recipe.geometryKey, cleaned, page.recipe.rotation - target.rotation)
                        }
                    }
                } finally {
                    cleaned?.recycle()
                }
                if (rendered == null) {
                    failed = true
                    return
                }
                val finished = rendered.copy(cleanupMissing = wantsCleanup && cleaned == null)
                if (detectTurn) orientationDecided += pageId
                if (session.page(pageId) == null) {
                    // Deleted (or the scan discarded) while rendering: do not leave its image behind.
                    scanRepository.discardPage(pageId)
                    cleaner.forget(pageId)
                    return
                }
                val turnedUpright = finished.recipe.rotation != target.rotation
                session.update(pageId) { current ->
                    if (turnedUpright && current.rotation == draft.rotation) {
                        // Recognition turned the page upright: the turn joins its recipe like the
                        // user's own, so the rotate buttons can turn it back.
                        current.copy(rotation = finished.recipe.rotation, finished = finished)
                    } else {
                        // Not turned, or the user turned the page meanwhile: their choice wins
                        // (the page is then rendered again their way).
                        current.copy(finished = finished)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "page_render_failed", e)
            failed = true
        } finally {
            mutablePending.update { it - pageId }
            if (failed) {
                if (session.page(pageId) != null) mutableFailed.update { it + pageId }
            } else if (session.page(pageId)?.isUpToDate == false) {
                // Changed again after the last round (or kept changing): go round once more.
                enqueue(pageId)
            }
        }
    }

    private companion object {
        const val TAG = "PageProcessor"

        /** Re-renders in a row for a page that keeps changing, before yielding to other pages. */
        const val MAX_ROUNDS = 4
    }
}
