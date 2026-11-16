package app.scanova.feature.scan.render

import android.graphics.Bitmap
import androidx.core.graphics.scale
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageFilters
import app.scanova.core.data.model.PageMesh
import app.scanova.core.data.model.PagePoint
import app.scanova.feature.scan.filter.FilterCatalogue
import app.scanova.feature.scan.filter.FilterEngine
import app.scanova.feature.scan.filter.LookSource
import app.scanova.feature.scan.filter.ServerCleanup
import app.scanova.feature.scan.imaging.Bitmaps
import javax.inject.Inject
import kotlin.math.max

/** The look of a page: its filter and the user's adjustments (the non-geometric part of a recipe). */
data class PageLook(
    val filterId: String = PageFilters.ORIGINAL,
    /** 0..1 */
    val strength: Float = 1f,
    /** -1..1 */
    val brightness: Float = 0f,
    /** -1..1 */
    val contrast: Float = 0f,
) {
    /** Same filter, adjustments back to neutral. */
    fun withoutAdjustments(): PageLook = copy(strength = 1f, brightness = 0f, contrast = 0f)

    val hasAdjustments: Boolean get() = strength < 1f || brightness != 0f || contrast != 0f
}

internal val PageEdit.look: PageLook
    get() = PageLook(FilterCatalogue.byId(filter).id, filterStrength.coerceIn(0f, 1f), brightness.coerceIn(-1f, 1f), contrast.coerceIn(-1f, 1f))

internal fun PageEdit.withLook(look: PageLook): PageEdit =
    copy(filter = look.filterId, filterStrength = look.strength, brightness = look.brightness, contrast = look.contrast)

/** The recipe without its look and markup: what the preview's base layer shows. */
internal fun PageEdit.geometryOnly(): PageEdit =
    PageEdit(sourceRotation = sourceRotation, crop = crop, rotation = rotation, flatten = flatten)

/** Identifies a page's geometry: previews made for one key are valid for any recipe with the same key. */
internal data class GeometryKey(val sourceRotation: Int, val crop: List<PagePoint>?, val rotation: Int, val flatten: PageMesh?)

internal val PageEdit.geometryKey: GeometryKey
    get() = GeometryKey(RecipeGeometry.normalizeDegrees(sourceRotation), crop, RecipeGeometry.normalizeDegrees(rotation), flatten)

/**
 * The large view of the page being edited, as layers the screen composes
 * live: [base] (flattened, unfiltered) under [filtered] at the filter
 * strength, both through the brightness/contrast matrix, markup on top. This
 * mirrors [PageRecipeRenderer.look] exactly, so slider changes show instantly
 * and match the saved page.
 */
class LiveLayers internal constructor(
    /** The page and geometry these layers were made for. */
    internal val pageId: String,
    internal val geometry: GeometryKey,
    val base: Bitmap,
    /** [base] with [filterId] applied; null for the original look (or while it is being made). */
    val filtered: Bitmap?,
    val filterId: String,
    /**
     * [filtered] is only the stand-in for the page's cleaned image (Server
     * clean-up), which is not there yet: being made, or clean-up failed.
     */
    val standIn: Boolean = false,
)

/** A filtered layer for the live view: [image] (null: the base itself) and whether it is a stand-in ([LiveLayers.standIn]). */
internal class LiveFilter(val image: Bitmap?, val standIn: Boolean)

/** Builds preview-size images for the live editing views. Heavy work: call on a background dispatcher. */
internal class PreviewMaker @Inject constructor(
    private val renderer: PageRecipeRenderer,
    private val filters: FilterEngine,
) {
    /** The page's geometry from [source] (the image the recipe applies to), at preview size. */
    fun base(source: Bitmap, edit: PageEdit): Bitmap = renderer.geometry(source, edit.geometryOnly(), BASE_LONG_EDGE)

    /**
     * [base] with filter [filterId]. For Server clean-up that is [cleanedJpeg]
     * (the page's cleaned image for this geometry, scaled to [base]) when
     * there is one, otherwise the stand-in look, marked as such — the same
     * rule as [PageRecipeRenderer.look].
     */
    fun filtered(base: Bitmap, filterId: String, cleanedJpeg: ByteArray? = null): LiveFilter {
        val source = ServerCleanup.source(filterId, hasCleaned = cleanedJpeg != null)
        if (source is LookSource.Cleaned) {
            fitted(checkNotNull(cleanedJpeg), base)?.let { return LiveFilter(it, standIn = false) }
        }
        val local = source as? LookSource.Local ?: LookSource.Local(ServerCleanup.STAND_IN, standIn = true)
        return LiveFilter(filters.apply(base, local.filterId).takeIf { it !== base }, local.standIn)
    }

    /**
     * Small images of the page in every catalogue look, by filter id, for the
     * filter row. Server clean-up shows [cleanedJpeg] when the page has a
     * cleaned image for this geometry, otherwise its stand-in.
     */
    fun filterThumbnails(base: Bitmap, cleanedJpeg: ByteArray? = null): Map<String, Bitmap> {
        val small = Bitmaps.thumbnail(base, THUMBNAIL_LONG_EDGE)
        val local = FilterCatalogue.all
            .filterNot { ServerCleanup.isCleanup(it.id) }
            .associate { filter -> filter.id to filters.apply(small, filter) }
        val cleanup = cleanedJpeg?.let(::cleanedThumbnail) ?: local.getValue(ServerCleanup.STAND_IN)
        return local + (ServerCleanup.ID to cleanup)
    }

    /** The filter row's Server clean-up thumbnail from the page's cleaned image; null when it cannot be decoded. */
    fun cleanedThumbnail(cleanedJpeg: ByteArray): Bitmap? = Bitmaps.decodeSampled(cleanedJpeg, THUMBNAIL_LONG_EDGE)

    /** [cleanedJpeg] decoded at exactly [base]'s size, or null when it cannot be decoded. */
    private fun fitted(cleanedJpeg: ByteArray, base: Bitmap): Bitmap? {
        val decoded = Bitmaps.decodeSampled(cleanedJpeg, max(base.width, base.height)) ?: return null
        if (decoded.width == base.width && decoded.height == base.height) return decoded
        val scaled = decoded.scale(base.width, base.height)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    companion object {
        /** Long side the source is decoded at for previews: a little over the base size, so scaling stays sharp. */
        const val SOURCE_LONG_EDGE = 2048

        /** Long side of the live page view. */
        const val BASE_LONG_EDGE = 1400

        /** Long side of the filter row's thumbnails. */
        const val THUMBNAIL_LONG_EDGE = 220
    }
}
