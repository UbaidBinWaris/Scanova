package app.scanova.feature.scan.idcard

import app.scanova.feature.scan.geometry.PixelSize
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.geometry.RectD
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Layout of an ID card scan: the card-shaped guide over the camera preview,
 * and the A4 page the two sides are put on (front in the upper half, back in
 * the lower half), at the card's real size so a printout is a true-size copy.
 *
 * Pure Kotlin, unit-tested.
 */
internal object IdCardLayout {
    /** ID-1 format (bank cards, most identity cards): 85.60 × 53.98 mm. */
    const val CARD_WIDTH_MM = 85.60
    const val CARD_HEIGHT_MM = 53.98
    const val CARD_ASPECT = CARD_WIDTH_MM / CARD_HEIGHT_MM

    const val A4_WIDTH_MM = 210.0
    const val A4_HEIGHT_MM = 297.0

    /** Space kept free around each card, as a fraction of the page width. */
    const val MARGIN_FRACTION = 0.06

    /** Width of the guide over the preview, as a fraction of the frame width (portrait frame). */
    const val GUIDE_WIDTH_FRACTION = 0.84

    /** Largest share of the frame height the guide may take (landscape frames). */
    private const val GUIDE_MAX_HEIGHT_FRACTION = 0.8

    /** A portrait A4 page whose longer side is [longEdge] pixels. */
    fun pageSize(longEdge: Int): PixelSize =
        PixelSize((longEdge * A4_WIDTH_MM / A4_HEIGHT_MM).roundToInt(), longEdge)

    /**
     * Where a card side of proportions [cardAspect] (width / height) goes on a
     * [pageWidth] × [pageHeight] page: true size (as if the page were A4),
     * centred in the upper ([upper] = true) or lower half, shrunk only if it
     * would not fit inside the margins.
     */
    fun place(pageWidth: Int, pageHeight: Int, cardAspect: Double, upper: Boolean): RectD {
        val width = pageWidth.toDouble()
        val height = pageHeight.toDouble()
        val aspect = cardAspect.takeIf { it.isFinite() && it > 0.0 } ?: CARD_ASPECT
        val margin = width * MARGIN_FRACTION
        val half = height / 2.0
        val availableWidth = max(1.0, width - 2 * margin)
        val availableHeight = max(1.0, half - 2 * margin)

        // The card's long side is 85.6 mm whichever way it is held.
        val longSide = width * CARD_WIDTH_MM / A4_WIDTH_MM
        var cardWidth = if (aspect >= 1.0) longSide else longSide * aspect
        var cardHeight = cardWidth / aspect
        val shrink = min(1.0, min(availableWidth / cardWidth, availableHeight / cardHeight))
        cardWidth *= shrink
        cardHeight *= shrink

        val top = (if (upper) 0.0 else half) + (half - cardHeight) / 2.0
        return RectD(left = (width - cardWidth) / 2.0, top = top, width = cardWidth, height = cardHeight)
    }

    /**
     * The card-shaped guide drawn over the camera preview, in fractions of the
     * upright frame whose width / height is [frameAspect]: a landscape card,
     * centred.
     */
    fun guide(frameAspect: Double): Quad {
        val aspect = frameAspect.takeIf { it.isFinite() && it > 0.0 } ?: (3.0 / 4.0)
        var widthFraction = GUIDE_WIDTH_FRACTION
        // Card height as a fraction of the frame height.
        var heightFraction = widthFraction * aspect / CARD_ASPECT
        if (heightFraction > GUIDE_MAX_HEIGHT_FRACTION) {
            widthFraction *= GUIDE_MAX_HEIGHT_FRACTION / heightFraction
            heightFraction = GUIDE_MAX_HEIGHT_FRACTION
        }
        val left = (1.0 - widthFraction) / 2.0
        val top = (1.0 - heightFraction) / 2.0
        return Quad(
            Point2(left, top),
            Point2(left + widthFraction, top),
            Point2(left + widthFraction, top + heightFraction),
            Point2(left, top + heightFraction),
        )
    }

    /**
     * Whether a detected outline is the card in the guide rather than
     * something else in view (the table, a sheet of paper under the card): its
     * centre must lie in the guide and its size must be comparable.
     */
    fun matchesGuide(detected: Quad, guide: Quad): Boolean {
        val center = centerOf(detected)
        val left = guide.corners.minOf { it.x }
        val right = guide.corners.maxOf { it.x }
        val top = guide.corners.minOf { it.y }
        val bottom = guide.corners.maxOf { it.y }
        val slackX = (right - left) * CENTER_SLACK
        val slackY = (bottom - top) * CENTER_SLACK
        val centered = center.x in (left - slackX)..(right + slackX) && center.y in (top - slackY)..(bottom + slackY)
        val guideArea = guide.area()
        if (!centered || guideArea <= 0.0) return false
        val ratio = detected.area() / guideArea
        return ratio in MIN_AREA_RATIO..MAX_AREA_RATIO
    }

    /** Width / height of a flattened side, from its pixel size; falls back to the ID-1 shape. */
    fun aspectOf(width: Int, height: Int): Double =
        if (width > 0 && height > 0) width.toDouble() / height else CARD_ASPECT

    /** True when [aspect] is close to an ID-1 card held either way. */
    fun looksLikeCard(aspect: Double): Boolean {
        val landscape = if (aspect >= 1.0) aspect else 1.0 / aspect
        return abs(landscape - CARD_ASPECT) / CARD_ASPECT < CARD_SHAPE_TOLERANCE
    }

    private fun centerOf(quad: Quad) = Point2(quad.corners.sumOf { it.x } / 4.0, quad.corners.sumOf { it.y } / 4.0)

    private const val CENTER_SLACK = 0.15
    private const val MIN_AREA_RATIO = 0.35
    private const val MAX_AREA_RATIO = 1.6
    private const val CARD_SHAPE_TOLERANCE = 0.3
}
