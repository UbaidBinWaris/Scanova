package app.scanova.feature.scan.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.scanova.core.data.model.MarkupStroke
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.filter.Adjustments
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.render.LiveLayers
import app.scanova.feature.scan.render.MarkupGeometry
import app.scanova.feature.scan.render.PageLook
import kotlin.math.roundToInt

/**
 * A page drawn from its live layers: the unfiltered base, the filtered page
 * over it at the filter strength, both through the brightness/contrast
 * matrix, and the markup on top — the same steps, in the same order, as the
 * final render. Slider changes therefore show at once and match the saved page.
 *
 * Fills its parent: size it with [pageAspectModifier] or similar.
 */
@Composable
internal fun LivePage(
    layers: LiveLayers,
    look: PageLook,
    markup: List<MarkupStroke>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colorFilter = remember(look.brightness, look.contrast) {
        if (Adjustments.isNeutral(look.brightness, look.contrast)) {
            null
        } else {
            ColorFilter.colorMatrix(ColorMatrix(Adjustments.brightnessContrast(look.brightness, look.contrast)))
        }
    }
    // A new filter fades in over the old one.
    Crossfade(
        targetState = layers,
        animationSpec = Motion.quick(),
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        label = "livePage",
    ) { shown ->
        val base = remember(shown.base) { shown.base.asImageBitmap() }
        val filtered = remember(shown.filtered) { shown.filtered?.asImageBitmap() }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val full = filtered == null || Adjustments.isFullStrength(look.strength)
            if (filtered == null || !full) {
                drawImage(base, dstSize = target, colorFilter = colorFilter, filterQuality = FilterQuality.Medium)
            }
            if (filtered != null) {
                drawImage(
                    filtered,
                    dstSize = target,
                    alpha = if (full) 1f else Adjustments.strengthAlpha(look.strength) / 255f,
                    colorFilter = colorFilter,
                    filterQuality = FilterQuality.Medium,
                )
            }
            drawMarkup(markup)
        }
    }
}

/** A stored page image (or its thumbnail while the sharper one loads). */
@Composable
internal fun PageImage(bitmap: Bitmap, contentDescription: String?, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Medium,
        modifier = modifier,
    )
}

/**
 * Draws markup strokes over a page that fills this scope: points are
 * fractions of the page, widths fractions of its width. Highlighter strokes
 * keep the darker of ink and colour, like the final render.
 */
internal fun DrawScope.drawMarkup(strokes: List<MarkupStroke>) {
    val width = size.width
    val height = size.height
    for (stroke in strokes) {
        if (stroke.points.isEmpty()) continue
        val path = Path()
        MarkupGeometry.trace(
            points = stroke.points.map { Point2(it.x.toDouble() * width, it.y.toDouble() * height) },
            moveTo = path::moveTo,
            lineTo = path::lineTo,
            quadTo = path::quadraticTo,
        )
        drawPath(
            path = path,
            color = Color(stroke.color.toInt()),
            style = Stroke(width = MarkupGeometry.widthPx(stroke.width, width), cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = if (MarkupGeometry.isHighlighter(stroke)) BlendMode.Darken else BlendMode.SrcOver,
        )
    }
}

/**
 * The frame a page sits in on the editing screens: the page's own shape,
 * centred, with a hairline edge, and overlays for "processing" and "failed".
 */
@Composable
internal fun PageFrame(
    aspect: Float,
    processing: Boolean,
    failed: Boolean,
    processingLabel: String,
    failedLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .aspectRatio(aspect.coerceIn(MIN_ASPECT, MAX_ASPECT))
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(HAIRLINE, MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            content()
            AnimatedVisibility(
                visible = processing || failed,
                enter = fadeIn(Motion.enter()),
                exit = fadeOut(Motion.exit()),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.sm),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        if (failed) {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(PROGRESS_SIZE))
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE), strokeWidth = 2.dp)
                        }
                        Text(text = if (failed) failedLabel else processingLabel, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** "Page 2 of 5" over the bottom of a page. Decorative: the page itself carries the same words. */
@Composable
internal fun PageNumberChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier.clearAndSetSemantics { },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )
    }
}

private val HAIRLINE = 1.dp
private val PROGRESS_SIZE = 16.dp
private const val MIN_ASPECT = 0.2f
private const val MAX_ASPECT = 5f
