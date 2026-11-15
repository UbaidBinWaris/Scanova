package app.scanova.feature.scan.geometry

/** A rectangle in view coordinates. */
data class RectD(val left: Double, val top: Double, val width: Double, val height: Double)

/** Conversions between camera-frame, upright-image and on-screen coordinates. */
object FrameMapping {

    /**
     * Where an image of the given size is drawn when it is scaled to fit
     * entirely inside a view and centred (letterboxed).
     */
    fun fitInside(imageWidth: Double, imageHeight: Double, viewWidth: Double, viewHeight: Double): RectD {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return RectD(0.0, 0.0, 0.0, 0.0)
        }
        val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return RectD((viewWidth - width) / 2.0, (viewHeight - height) / 2.0, width, height)
    }

    /**
     * Turns a point given as fractions (0..1) of a sensor frame into fractions
     * of the same frame after it has been rotated clockwise by [rotationDegrees]
     * to appear upright. CameraX reports that rotation with every frame.
     */
    fun rotateNormalized(point: Point2, rotationDegrees: Int): Point2 =
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> Point2(1.0 - point.y, point.x)
            180 -> Point2(1.0 - point.x, 1.0 - point.y)
            270 -> Point2(point.y, 1.0 - point.x)
            else -> point
        }

    /** Maps a point given as fractions of an image into view coordinates inside [drawn]. */
    fun toView(normalized: Point2, drawn: RectD): Point2 =
        Point2(drawn.left + normalized.x * drawn.width, drawn.top + normalized.y * drawn.height)

    /** Inverse of [toView], clamped to the image. */
    fun toNormalized(view: Point2, drawn: RectD): Point2 {
        if (drawn.width <= 0 || drawn.height <= 0) return Point2(0.0, 0.0)
        return Point2(
            ((view.x - drawn.left) / drawn.width).coerceIn(0.0, 1.0),
            ((view.y - drawn.top) / drawn.height).coerceIn(0.0, 1.0),
        )
    }
}
