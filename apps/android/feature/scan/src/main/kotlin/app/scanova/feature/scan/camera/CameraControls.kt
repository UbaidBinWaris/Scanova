package app.scanova.feature.scan.camera

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.geometry.FrameMapping
import app.scanova.feature.scan.geometry.Quad
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the person holding the phone should do next, in one short line. */
internal enum class Guidance(@param:StringRes val textRes: Int, val icon: ImageVector) {
    SEARCHING(R.string.feature_scan_camera_hint_searching, Icons.Outlined.CenterFocusWeak),
    MOVE_CLOSER(R.string.feature_scan_camera_hint_move_closer, Icons.Outlined.ZoomIn),
    HOLD_STEADY(R.string.feature_scan_camera_hint_hold_steady, Icons.Outlined.PanTool),
    READY(R.string.feature_scan_camera_hint_ready, Icons.Outlined.CheckCircle),
    CAPTURING(R.string.feature_scan_camera_hint_capturing, Icons.Outlined.PhotoCamera),
}

internal fun CameraUiState.guidance(): Guidance {
    val found = outline
    return when {
        isCapturing -> Guidance.CAPTURING
        found == null -> Guidance.SEARCHING
        found.area() < COMFORTABLE_PAGE_AREA -> Guidance.MOVE_CLOSER
        autoCapture -> Guidance.HOLD_STEADY
        else -> Guidance.READY
    }
}

/** Close on the left; auto-capture and the torch on the right, where the thumb is. */
@Composable
internal fun CameraTopBar(
    isRetake: Boolean,
    hasPages: Boolean,
    autoCapture: Boolean,
    onToggleAutoCapture: () -> Unit,
    torchAvailable: Boolean,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(
                    if (hasPages) R.string.feature_scan_camera_back_to_pages else R.string.feature_scan_camera_close,
                ),
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (isRetake) {
                Text(
                    text = stringResource(R.string.feature_scan_camera_retaking),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FilterChip(
            selected = autoCapture,
            onClick = onToggleAutoCapture,
            label = { Text(stringResource(R.string.feature_scan_camera_auto_capture), maxLines = 1) },
            leadingIcon = if (autoCapture) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
        )
        if (torchAvailable) {
            IconToggleButton(checked = torchOn, onCheckedChange = { onToggleTorch() }) {
                Icon(
                    imageVector = if (torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    contentDescription = stringResource(R.string.feature_scan_camera_torch),
                )
            }
        }
    }
}

/**
 * The live page outline. Corners glide between analysed frames (they arrive
 * about ten times a second) and the outline fades in and out instead of
 * blinking when detection briefly loses the page.
 */
@Composable
internal fun DocumentOutline(outline: Quad?, frameAspect: Float, modifier: Modifier = Modifier) {
    val corners = remember { List(4) { Animatable(Offset.Zero, Offset.VectorConverter) } }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(outline == null) {
        alpha.animateTo(if (outline == null) 0f else 1f, tween(OUTLINE_FADE_MILLIS))
    }
    LaunchedEffect(outline) {
        val target = outline ?: return@LaunchedEffect
        // A page that is (re)appearing jumps into place; one already shown glides.
        val jump = alpha.value < 0.05f
        target.corners.forEachIndexed { index, corner ->
            val position = Offset(corner.x.toFloat(), corner.y.toFloat())
            launch {
                if (jump) {
                    corners[index].snapTo(position)
                } else {
                    corners[index].animateTo(position, tween(OUTLINE_GLIDE_MILLIS, easing = LinearEasing))
                }
            }
        }
    }

    val stroke = MaterialTheme.colorScheme.primary
    val handle = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        val opacity = alpha.value
        if (opacity <= 0f || frameAspect <= 0f) return@Canvas
        val drawn = FrameMapping.fitInside(
            imageWidth = frameAspect.toDouble(),
            imageHeight = 1.0,
            viewWidth = size.width.toDouble(),
            viewHeight = size.height.toDouble(),
        )
        val points = corners.map { animated ->
            val corner = animated.value
            Offset(
                (drawn.left + corner.x * drawn.width).toFloat(),
                (drawn.top + corner.y * drawn.height).toFloat(),
            )
        }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (index in 1 until points.size) lineTo(points[index].x, points[index].y)
            close()
        }
        drawPath(path, stroke.copy(alpha = OUTLINE_FILL_ALPHA * opacity))
        drawPath(
            path,
            stroke.copy(alpha = opacity),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        points.forEach { point ->
            drawCircle(stroke.copy(alpha = opacity), radius = 6.dp.toPx(), center = point)
            drawCircle(handle.copy(alpha = opacity), radius = 3.dp.toPx(), center = point)
        }
    }
}

/** A short dim pulse over the preview when a picture is taken: the "shutter" feedback. */
@Composable
internal fun CaptureFlash(capturing: Boolean, modifier: Modifier = Modifier) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(capturing) {
        if (capturing) {
            flash.snapTo(FLASH_ALPHA)
            flash.animateTo(0f, tween(FLASH_MILLIS))
        }
    }
    val scrim = MaterialTheme.colorScheme.scrim
    Box(
        modifier = modifier
            .graphicsLayer { alpha = flash.value }
            .background(scrim),
    )
}

/**
 * The hint pill over the preview. A new hint must hold for a moment before it
 * replaces the current one, so flickering detection does not make the text
 * (and the screen reader) chatter.
 */
@Composable
internal fun GuidanceHint(guidance: Guidance, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(guidance) }
    LaunchedEffect(guidance) {
        if (guidance != Guidance.CAPTURING) delay(HINT_SETTLE_MILLIS)
        shown = guidance
    }
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                fadeIn(tween(HINT_FADE_MILLIS)) togetherWith fadeOut(tween(HINT_FADE_MILLIS)) using
                    SizeTransform(clip = false)
            },
            label = "guidance",
        ) { target ->
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(imageVector = target.icon, contentDescription = null, modifier = Modifier.size(HINT_ICON_SIZE))
                Text(text = stringResource(target.textRes), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The shutter: the screen's one primary action. A ring around it fills while
 * the page is held still, so auto-capture never comes as a surprise.
 */
@Composable
internal fun ShutterButton(
    steadyProgress: Float,
    showProgress: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.feature_scan_camera_take_picture)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, tween(PRESS_MILLIS), label = "shutterScale")
    val progress by animateFloatAsState(
        targetValue = if (showProgress) steadyProgress else 0f,
        animationSpec = tween(PROGRESS_MILLIS, easing = LinearEasing),
        label = "shutterProgress",
    )
    val ring = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val active = enabled && !busy

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SHUTTER_SIZE)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = active,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 4.dp.toPx()
            val ringRadius = size.minDimension / 2f - strokeWidth / 2f
            drawCircle(ring.copy(alpha = if (active) 1f else DISABLED_ALPHA), radius = ringRadius, style = Stroke(strokeWidth))
            if (progress > 0f) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
            }
            drawCircle(
                color = ring.copy(alpha = if (active) 1f else DISABLED_ALPHA),
                radius = ringRadius - strokeWidth - 3.dp.toPx(),
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(MinTouchTarget / 2),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

/** The pages scanned so far, as a small stack with a count. Opens the review. */
@Composable
internal fun PageStack(count: Int, thumbnail: Bitmap?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = pluralStringResource(R.plurals.feature_scan_camera_review_pages, count, count)
    val image = remember(thumbnail) { thumbnail?.asImageBitmap() }
    val shape = MaterialTheme.shapes.extraSmall
    val paper = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .sizeIn(minWidth = STACK_SIZE, minHeight = STACK_SIZE)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (count > 1) {
            Box(
                Modifier
                    .offset(x = Spacing.xs, y = -Spacing.xs)
                    .size(STACK_PAGE_WIDTH, STACK_PAGE_HEIGHT)
                    .background(paper.copy(alpha = STACK_BACK_ALPHA), shape),
            )
        }
        Box(
            modifier = Modifier
                .size(STACK_PAGE_WIDTH, STACK_PAGE_HEIGHT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(STACK_BORDER, paper, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .sizeIn(minWidth = BADGE_SIZE, minHeight = BADGE_SIZE),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = Spacing.xs)) {
                Text(text = stringResource(R.string.feature_scan_number, count), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private val TOP_BAR_HEIGHT = 64.dp
private val SHUTTER_SIZE = 80.dp
private val STACK_SIZE = 64.dp
private val STACK_PAGE_WIDTH = 40.dp
private val STACK_PAGE_HEIGHT = 52.dp
private val STACK_BORDER = 2.dp
private val BADGE_SIZE = 20.dp
private val HINT_ICON_SIZE = 18.dp

/** Dark backing behind text over the live picture; keeps it readable over white paper. */
internal const val SCRIM_ALPHA = 0.72f

/** A page covering less of the frame than this is far away: the photo would waste resolution. */
private const val COMFORTABLE_PAGE_AREA = 0.18

private const val OUTLINE_FILL_ALPHA = 0.16f
private const val OUTLINE_FADE_MILLIS = 180
private const val OUTLINE_GLIDE_MILLIS = 100
private const val FLASH_ALPHA = 0.6f
private const val FLASH_MILLIS = 220
private const val HINT_SETTLE_MILLIS = 350L
private const val HINT_FADE_MILLIS = 150
private const val PRESSED_SCALE = 0.92f
private const val PRESS_MILLIS = 100
private const val PROGRESS_MILLIS = 120
private const val DISABLED_ALPHA = 0.38f
private const val STACK_BACK_ALPHA = 0.35f
