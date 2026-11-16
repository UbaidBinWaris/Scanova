package app.scanova.feature.scan.render

import android.graphics.Bitmap
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.data.model.PageEdit
import app.scanova.feature.scan.cleanup.CleanedPages
import app.scanova.feature.scan.filter.ServerCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The live views of the page being edited. */
internal data class LiveState(
    val layers: LiveLayers? = null,
    /** The page in every look, small, by filter id: for the filter row. */
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    /** The Server clean-up thumbnail shows the page's cleaned image, not its stand-in. */
    val thumbnailsCleaned: Boolean = false,
    /** The page whose source could not be read. */
    val failedPageId: String? = null,
)

/**
 * Keeps the live layers ([LiveLayers]) and filter thumbnails of the page
 * being edited up to date, doing as little work as possible: a new filter only
 * re-filters the small base image, a new crop or rotation rebuilds the base
 * from the decoded source (kept for the current page), and brightness,
 * contrast and strength cost nothing here (the screen composes them).
 *
 * For Server clean-up the filtered layer is the page's cleaned image kept in
 * [cleanedPages]; until the server has made it, the stand-in look is shown
 * ([LiveLayers.standIn]) and swapped for the cleaned image as soon as it is kept.
 *
 * Shared by the scan review (source: the upright working copy) and the
 * document editor (source: the original). Runs in [scope] (the view model's,
 * on the main thread); pixel work goes to the default dispatcher.
 */
internal class LivePreviewController(
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val maker: PreviewMaker,
    /** Cleaned images of pages with the Server clean-up look, as the server made them. */
    private val cleanedPages: CleanedPages,
    /** Decodes the image the page's recipe applies to, at about [PreviewMaker.SOURCE_LONG_EDGE]. */
    private val loadSource: suspend (pageId: String) -> Bitmap?,
) {
    private val mutableState = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = mutableState.asStateFlow()

    private var cachedSource: Pair<String, Bitmap>? = null
    private var requested: Request? = null
    private var baseJob: Job? = null
    private var filterJob: Job? = null
    private var thumbnailJob: Job? = null

    private data class Request(val pageId: String, val geometry: GeometryKey, val filterId: String)

    init {
        // A page was cleaned (here or in the background): the page shown swaps its stand-in for it.
        scope.launch { cleanedPages.version.collect { onCleanedPagesChanged() } }
    }

    /** Shows page [pageId] as recipe [edit] describes it (only its geometry and filter matter here). */
    fun show(pageId: String, edit: PageEdit) {
        val request = Request(pageId, edit.geometryKey, edit.look.filterId)
        if (request == requested) return
        requested = request
        val layers = mutableState.value.layers
        if (layers != null && layers.pageId == pageId && layers.geometry == request.geometry) {
            if (layers.filterId != request.filterId) refilter(layers, request.filterId)
            return
        }
        rebuild(request, edit)
    }

    /** The decoded source of [pageId] (cached for the page being edited). */
    suspend fun source(pageId: String): Bitmap? {
        cachedSource?.takeIf { it.first == pageId }?.let { return it.second }
        val loaded = loadSource(pageId) ?: return null
        cachedSource = pageId to loaded
        return loaded
    }

    /** Forgets everything about [pageId] (deleted, or its source changed). */
    fun forget(pageId: String) {
        if (cachedSource?.first == pageId) cachedSource = null
        if (requested?.pageId == pageId) requested = null
        if (mutableState.value.layers?.pageId == pageId) {
            baseJob?.cancel()
            filterJob?.cancel()
            thumbnailJob?.cancel()
            mutableState.value = LiveState()
        }
    }

    private fun rebuild(request: Request, edit: PageEdit) {
        baseJob?.cancel()
        filterJob?.cancel()
        thumbnailJob?.cancel()
        baseJob = scope.launch {
            val source = source(request.pageId)
            if (source == null) {
                if (requested == request) mutableState.update { it.copy(failedPageId = request.pageId) }
                return@launch
            }
            val cleaned = cleanedPages.get(request.pageId, request.geometry)
            val built = withContext(dispatchers.default) {
                try {
                    val base = maker.base(source, edit)
                    Triple(base, maker.filtered(base, request.filterId, cleaned), maker.filterThumbnails(base, cleaned))
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
            val current = requested
            if (built == null) {
                if (current == request) mutableState.update { it.copy(failedPageId = request.pageId) }
                return@launch
            }
            // A different page or geometry was asked for meanwhile: this result is stale.
            if (current == null || current.pageId != request.pageId || current.geometry != request.geometry) return@launch
            val (base, filtered, thumbnails) = built
            val layers = LiveLayers(request.pageId, request.geometry, base, filtered.image, request.filterId, filtered.standIn)
            mutableState.value = LiveState(layers = layers, thumbnails = thumbnails, thumbnailsCleaned = cleaned != null)
            if (current.filterId != request.filterId) refilter(layers, current.filterId)
        }
    }

    private fun refilter(layers: LiveLayers, filterId: String) {
        filterJob?.cancel()
        filterJob = scope.launch {
            val cleaned = if (ServerCleanup.isCleanup(filterId)) cleanedPages.get(layers.pageId, layers.geometry) else null
            val filtered = withContext(dispatchers.default) {
                try {
                    maker.filtered(layers.base, filterId, cleaned)
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
            if (requested?.filterId != filterId || mutableState.value.layers?.base !== layers.base) return@launch
            mutableState.update {
                it.copy(
                    layers = LiveLayers(layers.pageId, layers.geometry, layers.base, filtered?.image, filterId, filtered?.standIn == true),
                )
            }
        }
    }

    /** A cleaned image was kept or dropped: if it is the shown page's, it replaces the stand-ins. */
    private fun onCleanedPagesChanged() {
        val state = mutableState.value
        val layers = state.layers ?: return
        if (!layers.standIn && state.thumbnailsCleaned) return
        if (!cleanedPages.contains(layers.pageId, layers.geometry)) return
        val current = requested
        if (layers.standIn && current == Request(layers.pageId, layers.geometry, layers.filterId)) refilter(layers, layers.filterId)
        if (!state.thumbnailsCleaned) refreshCleanedThumbnail(layers)
    }

    private fun refreshCleanedThumbnail(layers: LiveLayers) {
        thumbnailJob?.cancel()
        thumbnailJob = scope.launch {
            val cleaned = cleanedPages.get(layers.pageId, layers.geometry) ?: return@launch
            val thumbnail = withContext(dispatchers.default) {
                try {
                    maker.cleanedThumbnail(cleaned)
                } catch (e: OutOfMemoryError) {
                    null
                }
            } ?: return@launch
            val shown = mutableState.value.layers
            if (shown == null || shown.pageId != layers.pageId || shown.geometry != layers.geometry) return@launch
            mutableState.update { it.copy(thumbnails = it.thumbnails + (ServerCleanup.ID to thumbnail), thumbnailsCleaned = true) }
        }
    }
}
