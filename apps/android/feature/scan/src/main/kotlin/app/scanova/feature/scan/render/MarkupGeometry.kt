package app.scanova.feature.scan.render

import app.scanova.core.data.model.MarkupStroke
import app.scanova.core.data.model.MarkupTool
import app.scanova.core.data.model.PagePoint
import app.scanova.feature.scan.geometry.FrameMapping
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.RectD
import kotlin.math.hypot
import kotlin.math.max

/**
 * Where markup goes. Stroke points are fractions of the *finished* page and
 * widths are fractions of its width, so a stroke drawn on a small preview
 * lands in exactly the same place on the full-size page.
 *
 * Pure Kotlin, unit-tested.
 */
internal object MarkupGeometry {

    /** A touch at [view] (view pixels) on a page drawn into [drawn], as fractions of the page (clamped). */
    fun toPage(view: Point2, drawn: RectD): PagePoint {
        val point = FrameMapping.toNormalized(view, drawn)
        return PagePoint(point.x.toFloat(), point.y.toFloat())
    }

    /** Where page point [point] is drawn when the page is drawn into [drawn]. */
    fun toView(point: PagePoint, drawn: RectD): Point2 =
        FrameMapping.toView(Point2(point.x.toDouble(), point.y.toDouble()), drawn)

    /** A stroke's width in pixels on a page [pageWidth] pixels wide; never thinner than a pixel. */
    fun widthPx(width: Float, pageWidth: Float): Float = max(MIN_WIDTH_PX, width * pageWidth)

    /**
     * Whether a new point is far enough from the last one to keep, as a
     * fraction of the page width ([minStep]); dropping tiny steps keeps strokes
     * small without visibly changing them.
     */
    fun isNewPoint(last: PagePoint?, next: PagePoint, pageAspect: Float, minStep: Float = MIN_STEP): Boolean {
        if (last == null) return true
        // y fractions are of the height: convert to width units before measuring.
        val dx = (next.x - last.x).toDouble()
        val dy = ((next.y - last.y) / pageAspect.coerceAtLeast(MIN_ASPECT)).toDouble()
        return hypot(dx, dy) >= minStep
    }

    /**
     * The same strokes after the page has been turned [quarterTurns] clockwise.
     * The page's width changes when it turns sideways, so widths (fractions of
     * the width) are rescaled to keep the same thickness.
     */
    fun turn(strokes: List<MarkupStroke>, quarterTurns: Int, pageWidth: Float, pageHeight: Float): List<MarkupStroke> {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0 || strokes.isEmpty()) return strokes
        val widthScale = if (turns % 2 == 1 && pageHeight > 0f) pageWidth / pageHeight else 1f
        return strokes.map { stroke ->
            stroke.copy(
                width = stroke.width * widthScale,
                points = stroke.points.map { point ->
                    val turned = FrameMapping.rotateNormalized(Point2(point.x.toDouble(), point.y.toDouble()), turns * 90)
                    PagePoint(turned.x.toFloat(), turned.y.toFloat())
                },
            )
        }
    }

    /**
     * Traces a smooth path through [points]: straight to the first midpoint,
     * then quadratic curves through each point to the next midpoint. Both the
     * preview (Compose) and the render (android.graphics) draw with this.
     */
    inline fun trace(
        points: List<Point2>,
        moveTo: (Float, Float) -> Unit,
        lineTo: (Float, Float) -> Unit,
        quadTo: (Float, Float, Float, Float) -> Unit,
    ) {
        if (points.isEmpty()) return
        val first = points[0]
        moveTo(first.x.toFloat(), first.y.toFloat())
        if (points.size == 1) {
            // A tap: a zero-length line, which a round cap turns into a dot.
            lineTo(first.x.toFloat(), first.y.toFloat())
            return
        }
        for (index in 1 until points.size - 1) {
            val control = points[index]
            val next = points[index + 1]
            quadTo(
                control.x.toFloat(),
                control.y.toFloat(),
                ((control.x + next.x) / 2.0).toFloat(),
                ((control.y + next.y) / 2.0).toFloat(),
            )
        }
        val last = points.last()
        lineTo(last.x.toFloat(), last.y.toFloat())
    }

    /** Opacity a stroke is drawn with. Highlighter colours are multiplied into the page, so it stays opaque. */
    fun alphaOf(stroke: MarkupStroke): Float = ((stroke.color ushr 24) and 0xFF).toFloat() / 255f

    fun isHighlighter(stroke: MarkupStroke): Boolean = stroke.tool == MarkupTool.HIGHLIGHTER

    private const val MIN_WIDTH_PX = 1f
    private const val MIN_STEP = 0.002f
    private const val MIN_ASPECT = 0.05f
}
