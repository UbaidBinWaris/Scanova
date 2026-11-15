package app.scanova.feature.scan.editor

import androidx.annotation.StringRes
import app.scanova.core.data.model.MarkupTool
import app.scanova.feature.scan.R

/**
 * The colours and widths offered for markup. Widths are fractions of the
 * page width (see [app.scanova.core.data.model.MarkupStroke]); colours are
 * opaque ARGB. A highlighter colour is light, because it is drawn keeping the
 * darker of page and colour: text shows through it.
 */
internal object MarkupPalette {
    private val penColors = listOf(0xFF1A1C22L, 0xFF1E5EFFL, 0xFFD32F2FL, 0xFF1B7F3BL)
    private val highlighterColors = listOf(0xFFFFE34DL, 0xFF8CF29EL, 0xFFFFA8D6L, 0xFF8CD8FFL)

    private val penWidths = listOf(0.003f, 0.006f, 0.012f)
    private val highlighterWidths = listOf(0.016f, 0.028f, 0.045f)

    @StringRes
    private val penColorNames = listOf(
        R.string.feature_scan_markup_color_black,
        R.string.feature_scan_markup_color_blue,
        R.string.feature_scan_markup_color_red,
        R.string.feature_scan_markup_color_green,
    )

    @StringRes
    private val highlighterColorNames = listOf(
        R.string.feature_scan_markup_color_yellow,
        R.string.feature_scan_markup_color_green,
        R.string.feature_scan_markup_color_pink,
        R.string.feature_scan_markup_color_blue,
    )

    @StringRes
    val widthNames = listOf(
        R.string.feature_scan_markup_width_thin,
        R.string.feature_scan_markup_width_medium,
        R.string.feature_scan_markup_width_thick,
    )

    fun colors(tool: MarkupTool): List<Long> = if (tool == MarkupTool.PEN) penColors else highlighterColors

    fun colorNames(tool: MarkupTool): List<Int> = if (tool == MarkupTool.PEN) penColorNames else highlighterColorNames

    fun widths(tool: MarkupTool): List<Float> = if (tool == MarkupTool.PEN) penWidths else highlighterWidths
}
