package app.scanova.feature.scan.session

import android.graphics.Bitmap
import app.scanova.feature.scan.geometry.Quad
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The look applied to a page after it has been flattened. */
enum class ScanFilter { ORIGINAL, ENHANCED, GREYSCALE, BLACK_WHITE }

/** The result of cropping and filtering a page. The image itself is in the encrypted file store. */
data class FinishedPage(
    val width: Int,
    val height: Int,
    val ocrText: String,
    /** Small preview for the review list; the only copy of the page kept in memory. */
    val thumbnail: Bitmap,
)

/** One page of the scan in progress. */
data class DraftPage(
    val id: String,
    /** The crop outline, as fractions of the upright capture. */
    val outline: Quad,
    /** What edge detection proposed, to offer "reset". Null when nothing was found. */
    val detectedOutline: Quad?,
    val filter: ScanFilter,
    val captureWidth: Int,
    val captureHeight: Int,
    /** Null until the user has confirmed crop and filter. */
    val finished: FinishedPage?,
)

/**
 * The scan in progress, shared by the camera, crop and review screens. It lives
 * as long as the activity (surviving rotation) and holds only small things: the
 * captures and finished pages are encrypted on disk, addressed by page id.
 *
 * If the process is killed the session is gone; the files it left behind are
 * removed by the storage sweep at the next app start.
 */
@ActivityRetainedScoped
class ScanSession @Inject constructor() {
    private val mutablePages = MutableStateFlow<List<DraftPage>>(emptyList())
    val pages: StateFlow<List<DraftPage>> = mutablePages.asStateFlow()

    /** When set, the next capture replaces this page instead of being appended ("retake"). */
    @Volatile
    var replaceTargetId: String? = null

    fun page(id: String): DraftPage? = mutablePages.value.firstOrNull { it.id == id }

    /** Adds [page] at the end, or in place of the page it replaces. Returns the id of a replaced page. */
    fun addOrReplace(page: DraftPage): String? {
        val target = replaceTargetId
        replaceTargetId = null
        var replaced: String? = null
        mutablePages.update { pages ->
            val index = pages.indexOfFirst { it.id == target }
            if (target != null && index >= 0) {
                replaced = target
                pages.toMutableList().also { it[index] = page }
            } else {
                pages + page
            }
        }
        return replaced
    }

    fun update(id: String, change: (DraftPage) -> DraftPage) {
        mutablePages.update { pages -> pages.map { if (it.id == id) change(it) else it } }
    }

    fun remove(id: String) {
        mutablePages.update { pages -> pages.filterNot { it.id == id } }
    }

    /** Moves a page one position earlier ([offset] = -1) or later (+1). */
    fun move(id: String, offset: Int) {
        mutablePages.update { pages ->
            val from = pages.indexOfFirst { it.id == id }
            val to = from + offset
            if (from < 0 || to !in pages.indices) {
                pages
            } else {
                pages.toMutableList().also { it.add(to, it.removeAt(from)) }
            }
        }
    }

    /** Ends the scan. Returns the ids of all pages so their files can be removed. */
    fun clear(): List<String> {
        replaceTargetId = null
        val ids = mutablePages.value.map { it.id }
        mutablePages.value = emptyList()
        return ids
    }
}
