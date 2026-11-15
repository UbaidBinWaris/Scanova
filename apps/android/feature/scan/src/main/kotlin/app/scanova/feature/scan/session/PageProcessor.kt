package app.scanova.feature.scan.session

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.scan.ScanRepository
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
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
 */
@ActivityRetainedScoped
internal class PageProcessor @Inject constructor(
    private val session: ScanSession,
    private val scanRepository: ScanRepository,
    private val finisher: PageFinisher,
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

    init {
        lifecycle.addOnClearedListener { scope.cancel() }
        scope.launch {
            for (pageId in queue) process(pageId)
        }
    }

    /** Renders page [pageId] (again) from its current draft. */
    fun enqueue(pageId: String) {
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
                val draft = session.page(pageId) ?: return
                if (draft.isUpToDate) return
                val capture = when (val loaded = scanRepository.loadCapture(pageId)) {
                    is AppResult.Success -> loaded.value
                    is AppResult.Failure -> {
                        failed = true
                        return
                    }
                }
                val target = draft.toWorkingEdit()
                val previous = draft.finished
                // Text depends on what is on the page, not on its look.
                val sameText = previous != null &&
                    previous.recipe.crop == target.crop &&
                    previous.recipe.rotation == target.rotation
                val finished = finisher.finish(pageId, capture, target, knownText = previous?.ocrText?.takeIf { sameText })
                if (finished == null) {
                    failed = true
                    return
                }
                if (session.page(pageId) == null) {
                    // Deleted (or the scan discarded) while rendering: do not leave its image behind.
                    scanRepository.discardPage(pageId)
                    return
                }
                session.update(pageId) { it.copy(finished = finished) }
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
