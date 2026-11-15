package app.scanova.feature.scan.render

import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PagePoint
import app.scanova.feature.scan.geometry.FrameMapping
import app.scanova.feature.scan.geometry.OutputSize
import app.scanova.feature.scan.geometry.PixelSize
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The geometric part of a page recipe ([PageEdit]): turning the original
 * upright ([PageEdit.sourceRotation]), flattening the crop outline and turning
 * the result ([PageEdit.rotation]) — folded into a single perspective warp, so
 * a 50-megapixel original is never rotated as a whole bitmap.
 *
 * Pure Kotlin, unit-tested.
 */
internal object RecipeGeometry {

    /** Quarter turns (0..3) closest to [degrees], clockwise. */
    fun quarterTurns(degrees: Int): Int = ((Math.floorDiv(degrees + 45, 90) % 4) + 4) % 4

    /** [degrees] snapped to 0, 90, 180 or 270. */
    fun normalizeDegrees(degrees: Int): Int = quarterTurns(degrees) * 90

    /** [degrees] turned a further quarter turn clockwise ([turns] = 1) or anticlockwise (-1). */
    fun turn(degrees: Int, turns: Int): Int = normalizeDegrees(degrees + turns * 90)

    /** Size of an image of [width] × [height] once turned by [degrees]. */
    fun turnedSize(width: Int, height: Int, degrees: Int): PixelSize =
        if (quarterTurns(degrees) % 2 == 1) PixelSize(height, width) else PixelSize(width, height)

    /** A point in fractions of the upright image, as fractions of the original as stored ([sourceRotation] clockwise makes it upright). */
    fun uprightToRaw(point: Point2, sourceRotation: Int): Point2 =
        FrameMapping.rotateNormalized(point, (360 - normalizeDegrees(sourceRotation)) % 360)

    /** Inverse of [uprightToRaw]. */
    fun rawToUpright(point: Point2, sourceRotation: Int): Point2 =
        FrameMapping.rotateNormalized(point, normalizeDegrees(sourceRotation))

    /** The crop outline of a recipe, or null for "whole image" (absent or unusable). */
    fun cropQuad(crop: List<PagePoint>?): Quad? {
        if (crop == null || crop.size != 4) return null
        val points = crop.map { Point2(it.x.toDouble().coerceIn(0.0, 1.0), it.y.toDouble().coerceIn(0.0, 1.0)) }
        val quad = Quad(points[0], points[1], points[2], points[3])
        return quad.takeIf { it.isConvex() && it.area() > MIN_CROP_AREA }
    }

    fun toCrop(quad: Quad?): List<PagePoint>? = quad?.corners?.map { PagePoint(it.x.toFloat(), it.y.toFloat()) }

    /** The whole image as an outline, in fractions. */
    val WHOLE: Quad = Quad.inset(1.0, 1.0, 0.0)

    /**
     * How to warp a stored original of [rawWidth] × [rawHeight] pixels into the
     * page described by [edit] (geometry only), at most [maxLongEdge] pixels on
     * its longer side.
     */
    fun plan(rawWidth: Int, rawHeight: Int, edit: PageEdit, maxLongEdge: Int): WarpPlan {
        val upright = turnedSize(rawWidth, rawHeight, edit.sourceRotation)
        val uprightWidth = upright.width.toDouble()
        val uprightHeight = upright.height.toDouble()
        val crop = cropQuad(edit.crop)

        val outline = crop ?: WHOLE
        val size = if (crop != null) {
            OutputSize.forQuad(crop.scale(uprightWidth, uprightHeight), uprightWidth, uprightHeight, maxLongEdge)
        } else {
            fit(upright.width, upright.height, maxLongEdge)
        }

        // Corners of the outline in the stored original's pixels, still named
        // as they appear upright (top-left first, clockwise).
        val raw = outline.corners.map { corner ->
            val point = uprightToRaw(corner, edit.sourceRotation)
            Point2(point.x * rawWidth, point.y * rawHeight)
        }
        // Turning the flattened page k quarter turns clockwise moves its corner
        // i to corner (i + k) of the result, so result corner j comes from i = j - k.
        val turns = quarterTurns(edit.rotation)
        val ordered = List(4) { j -> raw[(j - turns + 4) % 4] }
        val output = turnedSize(size.width, size.height, edit.rotation)
        return WarpPlan(
            source = Quad(ordered[0], ordered[1], ordered[2], ordered[3]),
            width = output.width,
            height = output.height,
        )
    }

    /** [width] × [height] scaled down (never up) so the longer side is at most [maxLongEdge]. */
    fun fit(width: Int, height: Int, maxLongEdge: Int): PixelSize {
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge || longEdge <= 0) return PixelSize(max(1, width), max(1, height))
        val factor = maxLongEdge.toDouble() / longEdge
        return PixelSize(max(1, (width * factor).roundToInt()), max(1, (height * factor).roundToInt()))
    }

    private const val MIN_CROP_AREA = 1e-4
}

/**
 * A single warp from the stored original to the finished page geometry:
 * [source] (pixels of the original) maps corner for corner onto the
 * `width × height` result, top-left first, clockwise.
 */
internal data class WarpPlan(val source: Quad, val width: Int, val height: Int)
