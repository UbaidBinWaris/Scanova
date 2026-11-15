package app.scanova.feature.scan.render

import android.graphics.Bitmap
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageFilters
import app.scanova.core.data.model.PagePoint
import app.scanova.feature.scan.filter.FilterCatalogue
import app.scanova.feature.scan.filter.FilterEngine
import app.scanova.feature.scan.imaging.Bitmaps
import javax.inject.Inject

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
internal fun PageEdit.geometryOnly(): PageEdit = PageEdit(sourceRotation = sourceRotation, crop = crop, rotation = rotation)

/** Identifies a page's geometry: previews made for one key are valid for any recipe with the same key. */
internal data class GeometryKey(val sourceRotation: Int, val crop: List<PagePoint>?, val rotation: Int)

internal val PageEdit.geometryKey: GeometryKey
    get() = GeometryKey(RecipeGeometry.normalizeDegrees(sourceRotation), crop, RecipeGeometry.normalizeDegrees(rotation))

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
)

/** Builds preview-size images for the live editing views. Heavy work: call on a background dispatcher. */
internal class PreviewMaker @Inject constructor(
    private val renderer: PageRecipeRenderer,
    private val filters: FilterEngine,
) {
    /** The page's geometry from [source] (the image the recipe applies to), at preview size. */
    fun base(source: Bitmap, edit: PageEdit): Bitmap = renderer.geometry(source, edit.geometryOnly(), BASE_LONG_EDGE)

    /** [base] with filter [filterId], or null when the filter leaves it unchanged. */
    fun filtered(base: Bitmap, filterId: String): Bitmap? = filters.apply(base, filterId).takeIf { it !== base }

    /** Small images of the page in every catalogue look, by filter id, for the filter row. */
    fun filterThumbnails(base: Bitmap): Map<String, Bitmap> {
        val small = Bitmaps.thumbnail(base, THUMBNAIL_LONG_EDGE)
        return FilterCatalogue.all.associate { filter -> filter.id to filters.apply(small, filter) }
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
