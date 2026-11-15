package app.scanova.core.data.export

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How big a PDF page becomes as an image. PDF sizes are in points (1/72
 * inch); a page is drawn at [dpi] dots per inch, then scaled down if its long
 * edge would exceed the cap (a poster-sized page must not become a 100
 * megapixel bitmap).
 */
object PdfRenderSizing {
    const val POINTS_PER_INCH = 72.0

    /** Import quality: sharp enough to re-crop and read, small enough to store. */
    const val IMPORT_DPI = 200
    const val IMPORT_MAX_LONG_EDGE = 3000

    /** Upper bound for page images made for sharing. */
    const val EXPORT_MAX_LONG_EDGE = 2400

    fun pixelSize(widthPoints: Int, heightPoints: Int, dpi: Int, maxLongEdge: Int): PixelSize {
        require(dpi > 0 && maxLongEdge > 0) { "bad_render_limits" }
        val width = max(1, widthPoints)
        val height = max(1, heightPoints)
        val longEdgePoints = max(width, height)
        var scale = dpi / POINTS_PER_INCH
        if (longEdgePoints * scale > maxLongEdge) scale = maxLongEdge.toDouble() / longEdgePoints
        return PixelSize(
            width = max(1, (width * scale).roundToInt()),
            height = max(1, (height * scale).roundToInt()),
        )
    }

    /** The size that fits [size] inside a square of [maxLongEdge], never enlarging it. */
    fun fit(size: PixelSize, maxLongEdge: Int): PixelSize {
        if (size.longEdge <= maxLongEdge) return size
        val scale = maxLongEdge.toDouble() / size.longEdge
        return PixelSize(max(1, (size.width * scale).roundToInt()), max(1, (size.height * scale).roundToInt()))
    }
}
