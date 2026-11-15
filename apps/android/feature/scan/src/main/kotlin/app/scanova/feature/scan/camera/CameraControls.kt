package app.scanova.feature.scan.camera

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.AutoFixOff
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.MotionPhotosAuto
import androidx.compose.material.icons.outlined.MotionPhotosOff
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Sd
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.scanova.core.data.settings.FlashMode
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.core.designsystem.theme.pressScale
import app.scanova.feature.scan.R
import app.scanova.feature.scan.geometry.FrameMapping
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.idcard.IdCardLayout
import app.scanova.feature.scan.session.CameraMode
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the person holding the phone should do next, in one short line. */
internal enum class Guidance(@param:StringRes val textRes: Int, val icon: ImageVector) {
    SEARCHING(R.string.feature_scan_camera_hint_searching, Icons.Outlined.CenterFocusWeak),
    MOVE_CLOSER(R.string.feature_scan_camera_hint_move_closer, Icons.Outlined.ZoomIn),
    HOLD_STEADY(R.string.feature_scan_camera_hint_hold_steady, Icons.Outlined.PanTool),
    READY(R.string.feature_scan_camera_hint_ready, Icons.Outlined.CheckCircle),
    CAPTURING(R.string.feature_scan_camera_hint_capturing, Icons.Outlined.PhotoCamera),
    ID_FRONT(R.string.feature_scan_camera_hint_id_front, Icons.Outlined.Badge),
    ID_BACK(R.string.feature_scan_camera_hint_id_back, Icons.Outlined.FlipCameraAndroid),
    ID_READY(R.string.feature_scan_camera_hint_id_ready, Icons.Outlined.CheckCircle),
}

internal fun CameraUiState.guidance(): Guidance {
    val found = outline
    return when {
        isCapturing -> Guidance.CAPTURING
        mode == CameraMode.ID_CARD -> when {
            found != null && autoCapture -> Guidance.HOLD_STEADY
            found != null -> Guidance.ID_READY
            idSide == IdSide.BACK -> Guidance.ID_BACK
            else -> Guidance.ID_FRONT
        }
        found == null -> Guidance.SEARCHING
        found.area() < COMFORTABLE_PAGE_AREA -> Guidance.MOVE_CLOSER
        autoCapture -> Guidance.HOLD_STEADY
        else -> Guidance.READY
    }
}

/**
 * Close on the left; the camera's settings on the right, where the thumb is.
 * Every setting is an icon whose name and state are announced; a change is
 * also confirmed in words ([NoticePill]).
 */
@Composable
internal fun CameraTopBar(
    state: CameraUiState,
    onClose: () -> Unit,
    onFlash: () -> Unit,
    onGrid: () -> Unit,
    onHd: () -> Unit,
    onAutoCapture: () -> Unit,
    onAutoEnhance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = stringResource(R.string.feature_scan_state_on)
    val off = stringResource(R.string.feature_scan_state_off)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(
                    if (state.pageCount > 0) R.string.feature_scan_camera_back_to_pages else R.string.feature_scan_camera_close,
                ),
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.isRetake) {
                Text(
                    text = stringResource(R.string.feature_scan_camera_retaking),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
            }
        }
        if (state.flashAvailable) {
            CameraSetting(
                icon = when (state.flash) {
                    FlashMode.OFF -> Icons.Outlined.FlashOff
                    FlashMode.AUTO -> Icons.Outlined.FlashAuto
                    FlashMode.ON -> Icons.Outlined.FlashOn
                    FlashMode.TORCH -> Icons.Outlined.FlashlightOn
                },
                label = stringResource(R.string.feature_scan_camera_flash),
                state = stringResource(state.flash.labelRes()),
                active = state.flash != FlashMode.OFF,
                onClick = onFlash,
            )
        }
        CameraSetting(
            icon = if (state.grid) Icons.Outlined.GridOn else Icons.Outlined.GridOff,
            label = stringResource(R.string.feature_scan_camera_grid),
            state = if (state.grid) on else off,
            active = state.grid,
            onClick = onGrid,
        )
        CameraSetting(
            icon = if (state.hd) Icons.Outlined.Hd else Icons.Outlined.Sd,
            label = stringResource(R.string.feature_scan_camera_hd),
            state = if (state.hd) on else off,
            active = state.hd,
            enabled = !state.isCapturing,
            onClick = onHd,
        )
        CameraSetting(
            icon = if (state.autoCapture) Icons.Outlined.MotionPhotosAuto else Icons.Outlined.MotionPhotosOff,
            label = stringResource(R.string.feature_scan_camera_auto_capture),
            state = if (state.autoCapture) on else off,
            active = state.autoCapture,
            onClick = onAutoCapture,
        )
        CameraSetting(
            icon = if (state.autoEnhance) Icons.Outlined.AutoFixHigh else Icons.Outlined.AutoFixOff,
            label = stringResource(R.string.feature_scan_camera_auto_enhance),
            state = if (state.autoEnhance) on else off,
            active = state.autoEnhance,
            onClick = onAutoEnhance,
        )
    }
}

/** One setting in the camera's top bar: active settings are tinted and sit on a soft disc. */
@Composable
private fun CameraSetting(
    icon: ImageVector,
    label: String,
    state: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tint by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = Motion.quick(),
        label = "settingTint",
    )
    val disc by animateFloatAsState(if (active) ACTIVE_DISC_ALPHA else 0f, Motion.quick(), label = "settingDisc")
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MinTouchTarget)
            .pressScale(interaction)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                stateDescription = state
            },
    ) {
        Box(
            modifier = Modifier
                .size(SETTING_DISC_SIZE)
                .graphicsLayer { alpha = disc }
                .background(primary, CircleShape),
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA),
        )
    }
}

@StringRes
private fun FlashMode.labelRes(): Int = when (this) {
    FlashMode.OFF -> R.string.feature_scan_camera_flash_off
    FlashMode.AUTO -> R.string.feature_scan_camera_flash_auto
    FlashMode.ON -> R.string.feature_scan_camera_flash_on
    FlashMode.TORCH -> R.string.feature_scan_camera_flash_torch
}

@StringRes
private fun CameraNotice.textRes(): Int = when (this) {
    CameraNotice.FLASH_OFF -> R.string.feature_scan_camera_notice_flash_off
    CameraNotice.FLASH_AUTO -> R.string.feature_scan_camera_notice_flash_auto
    CameraNotice.FLASH_ON -> R.string.feature_scan_camera_notice_flash_on
    CameraNotice.TORCH -> R.string.feature_scan_camera_notice_torch
    CameraNotice.GRID_ON -> R.string.feature_scan_camera_notice_grid_on
    CameraNotice.GRID_OFF -> R.string.feature_scan_camera_notice_grid_off
    CameraNotice.HD_ON -> R.string.feature_scan_camera_notice_hd_on
    CameraNotice.HD_OFF -> R.string.feature_scan_camera_notice_hd_off
    CameraNotice.AUTO_CAPTURE_ON -> R.string.feature_scan_camera_notice_auto_capture_on
    CameraNotice.AUTO_CAPTURE_OFF -> R.string.feature_scan_camera_notice_auto_capture_off
    CameraNotice.AUTO_ENHANCE_ON -> R.string.feature_scan_camera_notice_auto_enhance_on
    CameraNotice.AUTO_ENHANCE_OFF -> R.string.feature_scan_camera_notice_auto_enhance_off
    CameraNotice.TURN_CARD -> R.string.feature_scan_camera_notice_turn_card
}

/** A short confirmation over the preview, gone after a moment. Read out by screen readers. */
@Composable
internal fun NoticePill(notice: CameraNotice?, noticeKey: Int, onShown: (Int) -> Unit, modifier: Modifier = Modifier) {
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(noticeKey, notice) {
        if (notice != null) {
            delay(NOTICE_VISIBLE_MILLIS)
            currentOnShown(noticeKey)
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(Motion.enter()),
        exit = fadeOut(Motion.exit()),
        modifier = modifier,
    ) {
        // Keeps showing the last notice while fading out.
        var shown by remember { mutableStateOf(notice) }
        if (notice != null) shown = notice
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = shown?.let { stringResource(it.textRes()) }.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }
    }
}

/** Document | ID card, under the preview, like the modes of a camera app. */
@Composable
internal fun ModeSwitcher(mode: CameraMode, enabled: Boolean, onSelect: (CameraMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeTab(R.string.feature_scan_camera_mode_document, mode == CameraMode.DOCUMENT, enabled) { onSelect(CameraMode.DOCUMENT) }
        ModeTab(R.string.feature_scan_camera_mode_id_card, mode == CameraMode.ID_CARD, enabled) { onSelect(CameraMode.ID_CARD) }
    }
}

@Composable
private fun ModeTab(@StringRes label: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.quick(),
        label = "modeColor",
    )
    val indicator by animateFloatAsState(if (selected) 1f else 0f, Motion.quick(), label = "modeIndicator")
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = color,
        )
        Box(
            modifier = Modifier
                .padding(top = Spacing.xs)
                .size(MODE_DOT_SIZE)
                .graphicsLayer {
                    alpha = indicator
                    scaleX = indicator
                    scaleY = indicator
                }
                .background(accent, CircleShape),
        )
    }
}

/** Rule-of-thirds lines over the picture, to help keep the page square to the camera. */
@Composable
internal fun GridOverlay(frameAspect: Float, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = GRID_ALPHA)
    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        val drawn = FrameMapping.fitInside(frameAspect.toDouble(), 1.0, size.width.toDouble(), size.height.toDouble())
        if (drawn.width <= 0.0) return@Canvas
        val stroke = 1.dp.toPx()
        for (step in 1..2) {
            val x = (drawn.left + drawn.width * step / 3.0).toFloat()
            val y = (drawn.top + drawn.height * step / 3.0).toFloat()
            drawLine(line, Offset(x, drawn.top.toFloat()), Offset(x, (drawn.top + drawn.height).toFloat()), stroke)
            drawLine(line, Offset(drawn.left.toFloat(), y), Offset((drawn.left + drawn.width).toFloat(), y), stroke)
        }
    }
}

/**
 * The card-shaped frame of ID card mode: everything around it is dimmed and
 * its corners light up in the accent colour once a card sits in it.
 */
@Composable
internal fun IdCardGuide(frameAspect: Float, cardFound: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val plain = MaterialTheme.colorScheme.onSurface
    val scrim = MaterialTheme.colorScheme.scrim
    val color by animateColorAsState(if (cardFound) accent else plain, Motion.quick(), label = "guideColor")
    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        val drawn = FrameMapping.fitInside(frameAspect.toDouble(), 1.0, size.width.toDouble(), size.height.toDouble())
        if (drawn.width <= 0.0) return@Canvas
        val guide = IdCardLayout.guide(frameAspect.toDouble())
        val left = (drawn.left + guide.topLeft.x * drawn.width).toFloat()
        val top = (drawn.top + guide.topLeft.y * drawn.height).toFloat()
        val right = (drawn.left + guide.bottomRight.x * drawn.width).toFloat()
        val bottom = (drawn.top + guide.bottomRight.y * drawn.height).toFloat()
        val radius = CornerRadius(GUIDE_RADIUS.toPx())
        val card = Path().apply { addRoundRect(RoundRect(left, top, right, bottom, radius)) }
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(drawn.left.toFloat(), drawn.top.toFloat(), (drawn.left + drawn.width).toFloat(), (drawn.top + drawn.height).toFloat()))
            addPath(card)
        }
        drawPath(outside, scrim.copy(alpha = GUIDE_DIM_ALPHA))
        drawPath(card, plain.copy(alpha = GUIDE_EDGE_ALPHA), style = Stroke(width = 1.5.dp.toPx()))
        // Corner brackets.
        val arm = (right - left) * BRACKET_FRACTION
        val width = 4.dp.toPx()
        val brackets = Path().apply {
            moveTo(left, top + arm); lineTo(left, top); lineTo(left + arm, top)
            moveTo(right - arm, top); lineTo(right, top); lineTo(right, top + arm)
            moveTo(right, bottom - arm); lineTo(right, bottom); lineTo(right - arm, bottom)
            moveTo(left + arm, bottom); lineTo(left, bottom); lineTo(left, bottom - arm)
        }
        drawPath(brackets, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Which side of the card comes next, with the front's picture once it is taken. */
@Composable
internal fun IdCardStep(side: IdSide, front: Bitmap?, modifier: Modifier = Modifier) {
    val image = remember(front) { front?.asImageBitmap() }
    val sideLabel = stringResource(if (side == IdSide.FRONT) R.string.feature_scan_camera_id_front else R.string.feature_scan_camera_id_back)
    val step = stringResource(R.string.feature_scan_camera_id_step, if (side == IdSide.FRONT) 1 else 2, 2)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.sm, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(ID_THUMB_WIDTH, ID_THUMB_HEIGHT)
                        .clip(MaterialTheme.shapes.extraSmall),
                )
            } else {
                Icon(Icons.Outlined.Badge, contentDescription = null, modifier = Modifier.padding(start = Spacing.xs))
            }
            Text(text = sideLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(text = step, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** A short dim blink over the preview when a picture is taken: the "shutter" feedback. */
@Composable
internal fun CaptureFlash(capturing: Boolean, modifier: Modifier = Modifier) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(capturing) {
        if (capturing) {
            flash.snapTo(FLASH_ALPHA)
            flash.animateTo(0f, tween(Motion.SHORT_MS + FLASH_EXTRA_MILLIS, easing = Motion.EmphasizedDecelerate))
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
 * The shutter: the screen's one primary action. It sinks when pressed and
 * springs back; a ring around it fills while the page is held still, so
 * auto-capture never comes as a surprise.
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
    val scale by animateFloatAsState(
        targetValue = if (pressed) SHUTTER_PRESSED_SCALE else 1f,
        animationSpec = if (pressed) Motion.quick() else Motion.bouncy(),
        label = "shutterScale",
    )
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
    val interaction = remember { MutableInteractionSource() }
    // The badge pops when the count changes.
    val badgeScale = remember { Animatable(1f) }
    LaunchedEffect(count) {
        badgeScale.snapTo(BADGE_POP_SCALE)
        badgeScale.animateTo(1f, Motion.bouncy())
    }
    Box(
        modifier = modifier
            .sizeIn(minWidth = STACK_SIZE, minHeight = STACK_SIZE)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
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
                .graphicsLayer {
                    scaleX = badgeScale.value
                    scaleY = badgeScale.value
                }
                .sizeIn(minWidth = BADGE_SIZE, minHeight = BADGE_SIZE),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = Spacing.xs)) {
                Text(text = stringResource(R.string.feature_scan_number, count), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * The page just taken, flying from the middle of the preview into the page
 * stack: a transform-only animation of a small image, so it stays smooth while
 * the camera keeps running.
 */
@Composable
internal fun FlyingPageOverlay(flying: FlyingPage?, from: Rect?, to: Rect?, onDone: (Long) -> Unit, modifier: Modifier = Modifier) {
    val page = flying ?: return
    val currentOnDone by rememberUpdatedState(onDone)
    if (from == null || to == null || from.width <= 0f || to.width <= 0f) {
        // The page stack appears together with the first page: wait a moment for its position.
        LaunchedEffect(page.key) {
            delay(FLY_WAIT_MILLIS)
            currentOnDone(page.key)
        }
        return
    }
    val progress = remember(page.key) { Animatable(0f) }
    LaunchedEffect(page.key) {
        progress.animateTo(1f, tween(Motion.LONG_MS, easing = Motion.Emphasized))
        currentOnDone(page.key)
    }
    val image = remember(page.key) { page.image.asImageBitmap() }
    val aspect = page.image.width.toFloat() / page.image.height.coerceAtLeast(1)
    // Start: the page centred over the preview at 70 % of its height. End: the stack's page.
    val startHeight = from.height * FLY_START_FRACTION
    val startWidth = startHeight * aspect
    val start = Rect(Offset(from.center.x - startWidth / 2f, from.center.y - startHeight / 2f), Size(startWidth, startHeight))
    val density = LocalDensity.current
    val endWidth = with(density) { STACK_PAGE_WIDTH.toPx() }
    val endScale = endWidth / startWidth
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset { IntOffset(start.left.roundToInt(), start.top.roundToInt()) }
                .size(with(density) { startWidth.toDp() }, with(density) { startHeight.toDp() })
                .graphicsLayer {
                    val t = progress.value
                    val scale = 1f + (endScale - 1f) * t
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    translationX = (to.center.x - start.center.x) * t
                    translationY = (to.center.y - start.center.y) * t
                    scaleX = scale
                    scaleY = scale
                    alpha = if (t < FLY_FADE_FROM) 1f else 1f - (t - FLY_FADE_FROM) / (1f - FLY_FADE_FROM)
                    shadowElevation = FLY_SHADOW * (1f - t)
                }
                .clearAndSetSemantics { },
        )
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
private val SETTING_DISC_SIZE = 36.dp
private val MODE_DOT_SIZE = 4.dp
private val GUIDE_RADIUS = 12.dp
private val ID_THUMB_WIDTH = 40.dp
private val ID_THUMB_HEIGHT = 26.dp

/** Dark backing behind text over the live picture; keeps it readable over white paper. */
internal const val SCRIM_ALPHA = 0.72f

/** A page covering less of the frame than this is far away: the photo would waste resolution. */
private const val COMFORTABLE_PAGE_AREA = 0.18

private const val OUTLINE_FILL_ALPHA = 0.16f
private const val OUTLINE_FADE_MILLIS = 180
private const val OUTLINE_GLIDE_MILLIS = 100
private const val FLASH_ALPHA = 0.6f
private const val FLASH_EXTRA_MILLIS = 30
private const val HINT_SETTLE_MILLIS = 350L
private const val HINT_FADE_MILLIS = 150
private const val NOTICE_VISIBLE_MILLIS = 1_600L
private const val SHUTTER_PRESSED_SCALE = 0.9f
private const val PROGRESS_MILLIS = 120
private const val DISABLED_ALPHA = 0.38f
private const val STACK_BACK_ALPHA = 0.35f
private const val ACTIVE_DISC_ALPHA = 0.18f
private const val GRID_ALPHA = 0.35f
private const val GUIDE_DIM_ALPHA = 0.5f
private const val GUIDE_EDGE_ALPHA = 0.5f
private const val BRACKET_FRACTION = 0.12f
private const val BADGE_POP_SCALE = 1.3f
private const val FLY_START_FRACTION = 0.7f
private const val FLY_FADE_FROM = 0.85f
private const val FLY_SHADOW = 24f
private const val FLY_WAIT_MILLIS = 300L
