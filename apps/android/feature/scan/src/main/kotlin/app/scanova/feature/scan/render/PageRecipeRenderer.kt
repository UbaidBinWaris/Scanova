package app.scanova.feature.scan.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.createBitmap
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.data.model.MarkupStroke
import app.scanova.core.data.model.PageEdit
import app.scanova.feature.scan.filter.Adjustments
import app.scanova.feature.scan.filter.FilterEngine
import app.scanova.feature.scan.geometry.Homography
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.imaging.WarpEngine
import javax.inject.Inject

/**
 * Makes a page from its source image and its recipe ([PageEdit]). The scan
 * flow and the editor both render through this one class, so the same recipe
 * always gives the same page.
 *
 * Order (as documented on [PageEdit]): turn the source upright → flatten the
 * crop outline → turn by the user's rotation (these three are one warp, see
 * [RecipeGeometry]) → filter at its strength → brightness / contrast → markup.
 *
 * The source is the page's original as stored (so [PageEdit.sourceRotation]
 * applies), or an image that is already upright, rendered with
 * `sourceRotation = 0` (the scan's working copy, a composed ID card page, or
 * the current image of a page without an original).
 *
 * Every method returns new bitmaps and leaves its inputs alone, except where
 * noted. Heavy work: call on a background dispatcher.
 */
internal class PageRecipeRenderer @Inject constructor(private val filters: FilterEngine) {

    /** A finished page and, when asked for, the same page before its look (for text recognition). */
    class Rendered(
        val page: Bitmap,
        /** The flattened page without filter, adjustments or markup; may be [page] itself. */
        val flat: Bitmap?,
    ) {
        fun recycle() {
            if (flat != null && flat !== page) flat.recycle()
            page.recycle()
        }
    }

    /** The whole recipe. */
    fun render(source: Bitmap, edit: PageEdit, maxLongEdge: Int, keepFlat: Boolean = false): Rendered {
        val flat = geometry(source, edit, maxLongEdge)
        var page: Bitmap? = null
        try {
            page = look(flat, edit)
            if (edit.markup.isNotEmpty()) {
                // Markup is drawn into the page; keep the unmarked page separate when it is wanted.
                if (page === flat && keepFlat) page = flat.copy(Bitmap.Config.ARGB_8888, true)
                drawMarkup(page, edit.markup)
            }
            val rendered = Rendered(page, if (keepFlat) flat else null)
            if (!keepFlat && page !== flat) flat.recycle()
            return rendered
        } catch (e: Throwable) {
            if (page != null && page !== flat) page.recycle()
            flat.recycle()
            throw e
        }
    }

    /** Geometry only: upright, flattened and turned, at most [maxLongEdge] on the longer side. Always a new bitmap. */
    fun geometry(source: Bitmap, edit: PageEdit, maxLongEdge: Int): Bitmap {
        val plan = RecipeGeometry.plan(source.width, source.height, edit, maxLongEdge)
        val transform = Homography.quadToRectangle(plan.source, plan.width.toDouble(), plan.height.toDouble())
        if (transform != null) {
            WarpEngine.warp(source, transform, plan.width, plan.height)?.let { return it }
        }
        return warpWithCanvas(source, plan)
    }

    /**
     * Filter at its strength, then brightness and contrast. The unfiltered page
     * and the filtered page each get the brightness/contrast matrix and the
     * filtered one is drawn over the other at the strength's opacity: exactly
     * what the live preview draws. Returns [flat] itself when nothing changes.
     */
    fun look(flat: Bitmap, edit: PageEdit): Bitmap {
        val filtered = filters.apply(flat, edit.filter)
        val neutral = Adjustments.isNeutral(edit.brightness, edit.contrast)
        val full = filtered === flat || Adjustments.isFullStrength(edit.filterStrength)
        if (neutral && full) return filtered
        try {
            val result = createBitmap(flat.width, flat.height)
            val canvas = Canvas(result)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            if (!neutral) {
                paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(Adjustments.brightnessContrast(edit.brightness, edit.contrast)))
            }
            if (!full) canvas.drawBitmap(flat, 0f, 0f, paint)
            paint.alpha = if (full) OPAQUE else Adjustments.strengthAlpha(edit.filterStrength)
            canvas.drawBitmap(filtered, 0f, 0f, paint)
            return result
        } finally {
            if (filtered !== flat) filtered.recycle()
        }
    }

    /** Draws [strokes] into [page] (which must be mutable). */
    fun drawMarkup(page: Bitmap, strokes: List<MarkupStroke>) {
        if (strokes.isEmpty()) return
        val canvas = Canvas(page)
        val width = page.width.toFloat()
        val height = page.height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Highlighter strokes keep the darker of ink and highlighter colour, so
        // text stays readable through them (and the page stays opaque).
        val darken = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            paint.color = stroke.color.toInt()
            paint.strokeWidth = MarkupGeometry.widthPx(stroke.width, width)
            paint.xfermode = if (MarkupGeometry.isHighlighter(stroke)) darken else null
            val path = Path()
            MarkupGeometry.trace(
                points = stroke.points.map { Point2(it.x.toDouble() * width, it.y.toDouble() * height) },
                moveTo = path::moveTo,
                lineTo = path::lineTo,
                quadTo = path::quadTo,
            )
            canvas.drawPath(path, paint)
        }
    }

    private fun warpWithCanvas(source: Bitmap, plan: WarpPlan): Bitmap {
        val result = createBitmap(plan.width, plan.height)
        val quad = plan.source
        val from = floatArrayOf(
            quad.topLeft.x.toFloat(), quad.topLeft.y.toFloat(),
            quad.topRight.x.toFloat(), quad.topRight.y.toFloat(),
            quad.bottomRight.x.toFloat(), quad.bottomRight.y.toFloat(),
            quad.bottomLeft.x.toFloat(), quad.bottomLeft.y.toFloat(),
        )
        val to = floatArrayOf(
            0f, 0f,
            plan.width.toFloat(), 0f,
            plan.width.toFloat(), plan.height.toFloat(),
            0f, plan.height.toFloat(),
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(from, 0, to, 0, 4)) {
            ScanovaLog.warn(TAG, "canvas_warp_degenerate")
            matrix.setRectToRect(
                android.graphics.RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
                android.graphics.RectF(0f, 0f, plan.width.toFloat(), plan.height.toFloat()),
                Matrix.ScaleToFit.FILL,
            )
        }
        Canvas(result).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return result
    }

    private companion object {
        const val TAG = "PageRenderer"
        const val OPAQUE = 255
    }
}
