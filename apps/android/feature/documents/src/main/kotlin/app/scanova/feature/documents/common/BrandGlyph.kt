package app.scanova.feature.documents.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Scanova's mark, 24×24: four scan corners around three lines of text. Tinted
 * by its Icon. The same drawing as the welcome screen's mark (feature modules
 * cannot share code, and the design system does not carry it yet).
 */
internal val ScanovaGlyph: ImageVector by lazy {
    val ink = SolidColor(Color.Black)
    ImageVector.Builder(
        name = "ScanovaGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = ink,
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 8.5f)
            verticalLineTo(6f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 6f, y1 = 4f)
            horizontalLineTo(8.5f)
            moveTo(15.5f, 4f)
            horizontalLineTo(18f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 6f)
            verticalLineTo(8.5f)
            moveTo(20f, 15.5f)
            verticalLineTo(18f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18f, y1 = 20f)
            horizontalLineTo(15.5f)
            moveTo(8.5f, 20f)
            horizontalLineTo(6f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 18f)
            verticalLineTo(15.5f)
        }
        path(stroke = ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(8.5f, 9.5f)
            horizontalLineTo(15.5f)
            moveTo(8.5f, 12f)
            horizontalLineTo(15.5f)
            moveTo(8.5f, 14.5f)
            horizontalLineTo(12.5f)
        }
    }.build()
}
