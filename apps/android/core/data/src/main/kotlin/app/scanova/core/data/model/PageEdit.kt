package app.scanova.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How a page's current image is made from its original: the complete recipe,
 * so the page can be edited again later (on this phone, after a restore, or in
 * the web app) without ever losing quality.
 *
 * Rendering order: upright the original ([sourceRotation]) → flatten the [crop]
 * outline → turn by [rotation] → apply [filter] at [filterStrength] → apply
 * [brightness] / [contrast] → draw [markup].
 *
 * The same JSON is stored on the server, encrypted, with the page's original.
 * Unknown fields are ignored when reading, so newer recipes stay readable.
 */
@Serializable
data class PageEdit(
    /** Recipe format; bump only for changes older readers would misread. */
    @SerialName("v") val version: Int = CURRENT_VERSION,
    /** Clockwise degrees (0, 90, 180, 270) that turn the original upright (camera orientation). */
    val sourceRotation: Int = 0,
    /**
     * Crop outline as four corners — top-left, top-right, bottom-right,
     * bottom-left — in fractions (0..1) of the *upright* original. Null means the
     * whole image, unflattened.
     */
    val crop: List<PagePoint>? = null,
    /** Clockwise degrees (0, 90, 180, 270) the user turned the flattened page. */
    val rotation: Int = 0,
    /** Filter id from the filter catalogue (see [PageFilters]). Unknown ids render as [PageFilters.ORIGINAL]. */
    val filter: String = PageFilters.ORIGINAL,
    /** How strongly the filter is applied, 0 (not at all) to 1 (fully). */
    val filterStrength: Float = 1f,
    /** -1..1; 0 leaves the image as the filter made it. */
    val brightness: Float = 0f,
    /** -1..1; 0 leaves the image as the filter made it. */
    val contrast: Float = 0f,
    /** Pen and highlighter strokes, drawn on top of the finished page. */
    val markup: List<MarkupStroke> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun encode(edit: PageEdit): String = json.encodeToString(serializer(), edit)

        /** Null for a missing or unreadable recipe: the page then edits from its current image. */
        fun decodeOrNull(text: String?): PageEdit? {
            if (text.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
        }
    }
}

/** A point in fractions (0..1) of an image's width ([x]) and height ([y]). */
@Serializable
data class PagePoint(val x: Float, val y: Float)

/**
 * One freehand stroke. Coordinates are fractions of the *finished* page, so
 * markup survives a re-render at another resolution.
 */
@Serializable
data class MarkupStroke(
    val tool: MarkupTool,
    /** ARGB colour, e.g. 0xFF1E5EFF. */
    val color: Long,
    /** Stroke width as a fraction of the page's width. */
    val width: Float,
    val points: List<PagePoint>,
)

@Serializable
enum class MarkupTool {
    @SerialName("pen") PEN,

    /** Drawn translucent, under the page's dark strokes. */
    @SerialName("highlighter") HIGHLIGHTER,
}

/**
 * Stable filter ids. They are stored in recipes (here and on the server), so an
 * id is never renamed or reused; new filters get new ids. The scan feature owns
 * how each one looks.
 */
object PageFilters {
    const val ORIGINAL = "original"
    const val ENHANCED = "enhanced"
    const val GREYSCALE = "greyscale"
    const val BLACK_WHITE = "black_white"
}
