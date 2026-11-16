package app.scanova.core.data.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * How a page's current image is made from its original: the complete recipe,
 * so the page can be edited again later (on this phone, after a restore, or in
 * the web app) without ever losing quality.
 *
 * Rendering order: upright the original ([sourceRotation]) → flatten the page:
 * along its [flatten] mesh when it has one (a curled page), otherwise the
 * [crop] outline → turn by [rotation] → apply [filter] at [filterStrength] →
 * apply [brightness] / [contrast] → draw [markup].
 *
 * The same JSON is stored on the server, encrypted, with the page's original.
 * Unknown fields are ignored when reading, so newer recipes stay readable (an
 * older app shows a flattened page by its [crop] outline instead).
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
    /**
     * Where each part of a curled page lies in the upright original (from the
     * on-device flattening model), so the page renders flat again without the
     * model. Null: the page is flattened by its [crop] outline alone. [crop]
     * stays the outline the user sees and edits.
     */
    val flatten: PageMesh? = null,
    /**
     * How the app laid the page out, when it did ([PageLayouts]); null for a
     * photographed page. An ID card page is composed by the app, so it is never
     * flattened.
     */
    val layout: String? = null,
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
 * A grid of [rows] × [cols] points laid over the flat page, row by row from
 * the top-left: point (r, c) is where the page's spot at fraction
 * (c / (cols − 1), r / (rows − 1)) of its width and height lies in the
 * upright original, in fractions (0..1) of the original's width and height.
 * Between points the page is interpolated bilinearly.
 *
 * Stored compactly: [points] is Base64 of little-endian unsigned 16-bit
 * values, x then y per point, each a fraction × 65535 (1/65535 ≈ 0.06 px on a
 * 4000 px photo). Build one with [of].
 */
@Serializable
data class PageMesh(
    val rows: Int,
    val cols: Int,
    val points: String,
) {
    /** x of every point (row by row), in fractions of the upright original; empty when the mesh is malformed. */
    @Transient
    val xs: FloatArray = decode(0)

    /** y of every point, as [xs]. */
    @Transient
    val ys: FloatArray = decode(1)

    /** Has [rows] × [cols] points (2 to 64 each way), all decoded. */
    val isValid: Boolean get() = hasValidSize(rows, cols) && xs.size == rows * cols

    private fun decode(axis: Int): FloatArray {
        // Each side is checked on its own: rows × cols of two large values would overflow.
        if (!hasValidSize(rows, cols)) return FloatArray(0)
        val count = rows * cols
        val bytes = runCatching { Base64.getDecoder().decode(points) }.getOrNull() ?: return FloatArray(0)
        if (bytes.size != count * 4) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { i -> (buffer.getShort((i * 2 + axis) * 2).toInt() and 0xFFFF) / SCALE }
    }

    companion object {
        private const val SCALE = 65535f

        /** Bounds what a recipe can make the app allocate: at most 64 × 64 points. */
        private const val MAX_SIDE = 64

        private fun hasValidSize(rows: Int, cols: Int) = rows in 2..MAX_SIDE && cols in 2..MAX_SIDE

        /** A mesh from its points' coordinates (fractions, clamped to 0..1), row by row. */
        fun of(rows: Int, cols: Int, xs: FloatArray, ys: FloatArray): PageMesh {
            require(hasValidSize(rows, cols)) { "mesh must be 2×2 to 64×64" }
            require(xs.size == rows * cols && ys.size == rows * cols) { "one x and one y per point" }
            val buffer = ByteBuffer.allocate(rows * cols * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in xs.indices) {
                buffer.putShort((xs[i].coerceIn(0f, 1f) * SCALE).roundToInt().toShort())
                buffer.putShort((ys[i].coerceIn(0f, 1f) * SCALE).roundToInt().toShort())
            }
            return PageMesh(rows, cols, Base64.getEncoder().encodeToString(buffer.array()))
        }
    }
}

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

/** Stable [PageEdit.layout] ids, stored in recipes: never renamed or reused. */
object PageLayouts {
    /** Both sides of an ID card, composed onto one page by the app. */
    const val ID_CARD = "id_card"
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

    /**
     * The flattened page cleaned on the owner's server (shadows, colour casts
     * and bleed-through removed). Apps that do not know it show the page as [ORIGINAL].
     */
    const val SERVER_CLEANUP = "server_cleanup"
}
