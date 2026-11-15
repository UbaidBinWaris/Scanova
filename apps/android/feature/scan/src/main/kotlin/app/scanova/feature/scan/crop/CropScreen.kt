package app.scanova.feature.scan.crop

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.geometry.FrameMapping
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.geometry.RectD
import kotlin.math.roundToInt

@Composable
internal fun CropRoute(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: CropViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }
    val abandon = {
        viewModel.onAbandon()
        onBack()
    }
    BackHandler(enabled = !state.isSaving, onBack = abandon)

    val preview = state.preview
    val outline = state.outline
    val ready = preview != null && outline != null
    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_scan_crop_title), onBack = abandon) },
        bottomBar = {
            if (ready) {
                CropControls(
                    state = state,
                    onAutoDetect = viewModel::onAutoDetect,
                    onUseWholePhoto = viewModel::onUseWholePhoto,
                    onConfirm = viewModel::onConfirm,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading -> LoadingState(message = stringResource(R.string.feature_scan_crop_loading))
                preview == null || outline == null -> ErrorState(
                    message = stringResource(R.string.feature_scan_crop_load_failed),
                    retryLabel = stringResource(R.string.feature_scan_crop_go_back),
                    onRetry = abandon,
                )
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(R.string.feature_scan_crop_instruction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
                    )
                    CropCanvas(
                        photo = preview,
                        outline = outline,
                        enabled = !state.isSaving,
                        onCornerMoved = viewModel::onCornerMoved,
                        onCornerNudged = viewModel::onCornerNudged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CropControls(
    state: CropUiState,
    onAutoDetect: () -> Unit,
    onUseWholePhoto: () -> Unit,
    onConfirm: () -> Unit,
) {
    val busy = state.isSaving || state.isDetecting
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AnimatedVisibility(
                visible = state.problem == CropProblem.SAVE_FAILED || state.problem == CropProblem.NOTHING_DETECTED,
                enter = fadeIn(tween(MOTION_MILLIS)) + expandVertically(tween(MOTION_MILLIS)),
                exit = fadeOut(tween(MOTION_MILLIS)) + shrinkVertically(tween(MOTION_MILLIS)),
            ) {
                val saveFailed = state.problem == CropProblem.SAVE_FAILED
                MessageBanner(
                    text = stringResource(
                        if (saveFailed) R.string.feature_scan_crop_save_failed else R.string.feature_scan_crop_nothing_detected,
                    ),
                    tone = if (saveFailed) BannerTone.Error else BannerTone.Info,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SecondaryButton(
                    text = stringResource(R.string.feature_scan_crop_auto_detect),
                    onClick = onAutoDetect,
                    enabled = !busy,
                    icon = Icons.Outlined.AutoFixHigh,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = stringResource(R.string.feature_scan_crop_whole_photo),
                    onClick = onUseWholePhoto,
                    enabled = !busy,
                    icon = Icons.Outlined.Fullscreen,
                    modifier = Modifier.weight(1f),
                )
            }
            PrimaryButton(
                text = stringResource(
                    if (state.isSaving) R.string.feature_scan_crop_processing else R.string.feature_scan_crop_confirm,
                ),
                onClick = onConfirm,
                loading = state.isSaving,
                enabled = !state.isDetecting,
                icon = Icons.Outlined.Check,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The photo with a four-corner outline that can be dragged into place.
 *
 * - Touch: a drag that starts near a corner moves that corner. While dragging,
 *   a magnifier shows the pixels under the finger, which would otherwise hide
 *   exactly the spot being aligned.
 * - Screen readers and switch access: each corner is a focusable element with
 *   "move up / down / left / right" actions.
 *
 * The photo is inset from the edges of the area so a corner sitting on the
 * photo's border can still be grabbed and its handle is fully visible.
 */
@Composable
private fun CropCanvas(
    photo: Bitmap,
    outline: Quad,
    enabled: Boolean,
    onCornerMoved: (Int, Point2) -> Unit,
    onCornerNudged: (Int, Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image: ImageBitmap = remember(photo) { photo.asImageBitmap() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var draggedCorner by remember { mutableStateOf<Int?>(null) }
    val currentOutline by rememberUpdatedState(outline)
    val currentOnCornerMoved by rememberUpdatedState(onCornerMoved)
    val density = LocalDensity.current
    val touchRadius = with(density) { TOUCH_RADIUS.toPx() }
    val inset = with(density) { Spacing.xl.toPx().toDouble() }
    val handleTouchSize = with(density) { MinTouchTarget.roundToPx() }

    val drawn = remember(viewSize, photo, inset) {
        val fitted = FrameMapping.fitInside(
            imageWidth = photo.width.toDouble(),
            imageHeight = photo.height.toDouble(),
            viewWidth = (viewSize.width - 2 * inset).coerceAtLeast(0.0),
            viewHeight = (viewSize.height - 2 * inset).coerceAtLeast(0.0),
        )
        fitted.copy(left = fitted.left + inset, top = fitted.top + inset)
    }
    val description = stringResource(R.string.feature_scan_crop_image_description)
    val outlineColor = MaterialTheme.colorScheme.primary
    val handleRing = MaterialTheme.colorScheme.onPrimary
    val scrim = MaterialTheme.colorScheme.scrim

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .onSizeChanged { viewSize = it },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(drawn, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { start ->
                            draggedCorner = nearestCorner(currentOutline, drawn, start, touchRadius)
                        },
                        onDrag = { change, _ ->
                            draggedCorner?.let { index ->
                                val position = Point2(change.position.x.toDouble(), change.position.y.toDouble())
                                currentOnCornerMoved(index, FrameMapping.toNormalized(position, drawn))
                            }
                            change.consume()
                        },
                        onDragEnd = { draggedCorner = null },
                        onDragCancel = { draggedCorner = null },
                    )
                },
        ) {
            if (drawn.width <= 0.0) return@Canvas
            drawImage(
                image = image,
                dstOffset = IntOffset(drawn.left.roundToInt(), drawn.top.roundToInt()),
                dstSize = IntSize(drawn.width.roundToInt(), drawn.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            val corners = outline.corners.map { it.toOffset(drawn) }
            drawOutline(corners, drawn, outlineColor, handleRing, scrim, draggedCorner)
            draggedCorner?.let { index ->
                drawMagnifier(image, outline.corners[index], corners[index], drawn, outlineColor, handleRing, scrim)
            }
        }

        // Invisible, focusable stand-ins for the corners. They do not take touch
        // input (dragging is handled by the canvas above); they exist so that
        // assistive technology can find and move each corner.
        val cornerLabels = listOf(
            stringResource(R.string.feature_scan_crop_corner_top_left),
            stringResource(R.string.feature_scan_crop_corner_top_right),
            stringResource(R.string.feature_scan_crop_corner_bottom_right),
            stringResource(R.string.feature_scan_crop_corner_bottom_left),
        )
        val moveUp = stringResource(R.string.feature_scan_crop_move_up)
        val moveDown = stringResource(R.string.feature_scan_crop_move_down)
        val moveLeft = stringResource(R.string.feature_scan_crop_move_left)
        val moveRight = stringResource(R.string.feature_scan_crop_move_right)
        if (drawn.width > 0.0) {
            outline.corners.forEachIndexed { index, corner ->
                val position = corner.toOffset(drawn)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (position.x - handleTouchSize / 2f).roundToInt(),
                                (position.y - handleTouchSize / 2f).roundToInt(),
                            )
                        }
                        .size(MinTouchTarget)
                        .semantics {
                            contentDescription = cornerLabels[index]
                            role = Role.Button
                            if (enabled) {
                                customActions = listOf(
                                    CustomAccessibilityAction(moveUp) { onCornerNudged(index, 0.0, -NUDGE); true },
                                    CustomAccessibilityAction(moveDown) { onCornerNudged(index, 0.0, NUDGE); true },
                                    CustomAccessibilityAction(moveLeft) { onCornerNudged(index, -NUDGE, 0.0); true },
                                    CustomAccessibilityAction(moveRight) { onCornerNudged(index, NUDGE, 0.0); true },
                                )
                            }
                        },
                )
            }
        }
    }
}

private fun Point2.toOffset(drawn: RectD): Offset {
    val point = FrameMapping.toView(this, drawn)
    return Offset(point.x.toFloat(), point.y.toFloat())
}

/** Index of the corner closest to [touch], or null when none is within [radius]. */
private fun nearestCorner(outline: Quad, drawn: RectD, touch: Offset, radius: Float): Int? {
    val distances = outline.corners.map { (it.toOffset(drawn) - touch).getDistance() }
    val index = distances.indices.minByOrNull { distances[it] } ?: return null
    return index.takeIf { distances[it] <= radius }
}

private fun DrawScope.drawOutline(
    corners: List<Offset>,
    drawn: RectD,
    outlineColor: Color,
    handleRing: Color,
    scrim: Color,
    draggedCorner: Int?,
) {
    val page = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (index in 1 until corners.size) lineTo(corners[index].x, corners[index].y)
        close()
    }
    // Dim everything outside the page so the selected area stands out.
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(
            Rect(
                offset = Offset(drawn.left.toFloat(), drawn.top.toFloat()),
                size = Size(drawn.width.toFloat(), drawn.height.toFloat()),
            ),
        )
        addPath(page)
    }
    drawPath(outside, scrim.copy(alpha = OUTSIDE_DIM_ALPHA))
    // A dark hairline under the coloured edge keeps it visible on any background.
    drawPath(page, scrim.copy(alpha = HALO_ALPHA), style = Stroke(width = 5.dp.toPx(), join = StrokeJoin.Round))
    drawPath(page, outlineColor, style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round))

    corners.forEachIndexed { index, corner ->
        val radius = if (index == draggedCorner) HANDLE_RADIUS_DRAGGING.toPx() else HANDLE_RADIUS.toPx()
        // Three tones (dark halo, light ring, accent centre) keep the handle
        // visible on white paper, dark tables and everything between.
        drawCircle(scrim.copy(alpha = HALO_ALPHA), radius = radius + 4.dp.toPx(), center = corner)
        drawCircle(handleRing, radius = radius + 2.dp.toPx(), center = corner)
        drawCircle(outlineColor, radius = radius, center = corner)
    }
}

/**
 * Draws an enlarged view of the photo around the corner being dragged. It sits
 * in the top corner of the image area farthest from the finger.
 */
private fun DrawScope.drawMagnifier(
    image: ImageBitmap,
    corner: Point2,
    cornerOnScreen: Offset,
    drawn: RectD,
    ringColor: Color,
    haloColor: Color,
    backdrop: Color,
) {
    val radius = MAGNIFIER_RADIUS.toPx()
    val margin = Spacing.md.toPx()
    val onLeftHalf = cornerOnScreen.x < size.width / 2f
    val center = Offset(
        x = if (onLeftHalf) size.width - radius - margin else radius + margin,
        y = radius + margin,
    )
    val lens = Path().apply { addOval(Rect(center = center, radius = radius)) }

    clipPath(lens) {
        drawRect(backdrop, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
        // The whole photo is drawn enlarged, positioned so the corner's pixel
        // falls on the centre of the lens; the clip shows only the neighbourhood.
        val scale = (drawn.width / image.width).toFloat() * MAGNIFICATION
        val left = center.x - (corner.x * image.width).toFloat() * scale
        val top = center.y - (corner.y * image.height).toFloat() * scale
        drawImage(
            image = image,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt()),
        )
    }
    drawCircle(haloColor, radius = radius + 1.5.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
    drawCircle(ringColor, radius = radius, center = center, style = Stroke(width = 3.dp.toPx()))
    // Crosshair marking the exact point the corner sits on.
    val arm = Spacing.md.toPx()
    drawLine(ringColor, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = 1.5.dp.toPx())
    drawLine(ringColor, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = 1.5.dp.toPx())
}

private val TOUCH_RADIUS = 40.dp
private val HANDLE_RADIUS = 12.dp
private val HANDLE_RADIUS_DRAGGING = 16.dp
private val MAGNIFIER_RADIUS = 60.dp
private const val MAGNIFICATION = 2.5f
private const val OUTSIDE_DIM_ALPHA = 0.55f
private const val HALO_ALPHA = 0.45f
private const val MOTION_MILLIS = 200

/** Step of one accessibility "move" action, as a fraction of the photo. */
private const val NUDGE = 0.02
