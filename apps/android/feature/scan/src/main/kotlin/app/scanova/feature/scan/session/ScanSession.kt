package app.scanova.feature.scan.session

import android.graphics.Bitmap
import app.scanova.core.data.model.PageEdit
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.render.PageLook
import app.scanova.feature.scan.render.RecipeGeometry
import app.scanova.feature.scan.render.withLook
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the camera is scanning. */
enum class CameraMode { DOCUMENT, ID_CARD }

/** The result of rendering a page. The image itself is in the encrypted file store. */
data class FinishedPage(
    val width: Int,
    val height: Int,
    val ocrText: String,
    /** Small preview for lists; the only copy of the page kept in memory. */
    val thumbnail: Bitmap,
    /** The recipe the stored image was rendered with (working copy as source, so `sourceRotation` = 0 here). */
    val recipe: PageEdit,
)

/** One page of the scan in progress. */
data class DraftPage(
    val id: String,
    /** An ID card page holds both sides of a card, composed from two photos. */
    val mode: CameraMode = CameraMode.DOCUMENT,
    /** The crop outline as fractions of the upright capture; null for the whole image. */
    val outline: Quad?,
    /** What edge detection proposed, to offer "reset". Null when nothing was found. */
    val detectedOutline: Quad?,
    val look: PageLook,
    /** Clockwise quarter turns the user gave the page, in degrees. */
    val rotation: Int = 0,
    /** Clockwise degrees that make the stored original upright (the camera's orientation). */
    val sourceRotation: Int = 0,
    val captureWidth: Int,
    val captureHeight: Int,
    /** A quick, unfiltered preview made at capture time, shown until the page is [finished]. */
    val quickThumbnail: Bitmap? = null,
    /** Null until the page has been rendered and stored. */
    val finished: FinishedPage?,
) {
    /**
     * The page's recipe for its original: crop in fractions of the upright
     * capture (which is the upright original, scaled down), plus look and turns.
     */
    fun toEdit(): PageEdit = PageEdit(
        sourceRotation = RecipeGeometry.normalizeDegrees(sourceRotation),
        crop = RecipeGeometry.toCrop(outline),
        rotation = RecipeGeometry.normalizeDegrees(rotation),
    ).withLook(look)

    /** The recipe for the upright working copy the scan renders from. */
    fun toWorkingEdit(): PageEdit = toEdit().copy(sourceRotation = 0)

    /** The stored image matches the page as it is now. */
    val isUpToDate: Boolean get() = finished?.recipe == toWorkingEdit()

    /** Best small image available right now. */
    val thumbnail: Bitmap? get() = finished?.thumbnail ?: quickThumbnail
}

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

    /** The camera mode last used in this scan; the camera reopens in it. */
    @Volatile
    var lastMode: CameraMode? = null

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

    fun updateAll(change: (DraftPage) -> DraftPage) {
        mutablePages.update { pages -> pages.map(change) }
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
        lastMode = null
        val ids = mutablePages.value.map { it.id }
        mutablePages.value = emptyList()
        return ids
    }
}
