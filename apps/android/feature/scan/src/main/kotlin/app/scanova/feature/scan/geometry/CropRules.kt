package app.scanova.feature.scan.geometry

/** Rules for dragging the corners of a crop outline. Pure Kotlin, unit-tested. */
object CropRules {
    /** Smallest outline accepted, as a fraction of the image. */
    const val MIN_AREA = 0.01

    /**
     * [outline] with corner [index] moved to [position] (fractions, clamped to
     * the image), or null when the move would fold the page over itself or
     * make it too small.
     */
    fun moveCorner(outline: Quad, index: Int, position: Point2): Quad? {
        val clamped = Point2(position.x.coerceIn(0.0, 1.0), position.y.coerceIn(0.0, 1.0))
        val moved = outline.withCorner(index, clamped)
        return moved.takeIf { it.isConvex() && it.area() > MIN_AREA }
    }

    /** True when [outline] is the whole image (within a hair). */
    fun isWholeImage(outline: Quad, tolerance: Double = 1e-3): Boolean =
        outline.maxCornerDistance(Quad.inset(1.0, 1.0, 0.0)) <= tolerance
}
