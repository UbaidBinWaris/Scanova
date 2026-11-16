package app.scanova.feature.scan.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.IconTile
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.ScanovaTheme
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.session.CameraMode
import app.scanova.feature.scan.ui.LightSystemBarIcons
import kotlinx.coroutines.delay

/**
 * The camera: scan pages one after another (they fly into the page stack and
 * are processed in the background), or both sides of an ID card.
 *
 * @param idCard the scan was started as an ID card scan: the camera opens in that mode.
 */
@Composable
internal fun CameraRoute(
    idCard: Boolean,
    onReview: () -> Unit,
    onClose: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) { viewModel.start(idCard) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    // Android stops showing its prompt after repeated refusals; then only the app settings can grant it.
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permanentlyDenied = !granted &&
            context.findActivity()?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } ?: true
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // Back from the system settings, where the permission may just have been granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = context.hasCameraPermission()
    }

    // A retake or a finished ID card goes straight back to the pages.
    LaunchedEffect(state.openReview) {
        if (state.openReview) {
            viewModel.onReviewOpened()
            onReview()
        }
    }

    val leave = {
        viewModel.onLeaving()
        // With pages already scanned, leaving returns to them instead of dropping the scan.
        if (state.pageCount > 0) onReview() else onClose()
    }
    BackHandler(onBack = leave)

    // Outside the camera's own dark theme below, so that leaving the camera
    // restores the icons of the app's theme, not of the camera's.
    LightSystemBarIcons()
    // The camera is always dark, whatever the system theme: bright chrome around
    // the preview would glare and make the picture harder to judge.
    ScanovaTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
            if (hasPermission) {
                CameraContent(
                    state = state,
                    viewModel = viewModel,
                    onClose = leave,
                    onReview = {
                        viewModel.onLeaving()
                        onReview()
                    },
                )
            } else {
                PermissionScreen(
                    permanentlyDenied = permanentlyDenied,
                    hasPages = state.pageCount > 0,
                    onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onClose = leave,
                )
            }
        }
    }
}

@Composable
private fun CameraContent(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onClose: () -> Unit,
    onReview: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val letterbox = MaterialTheme.colorScheme.surfaceContainerLowest
    val previewView = remember {
        PreviewView(context).apply {
            // The whole frame is shown (letterboxed), so the outline drawn on top
            // corresponds one-to-one to what the photo will contain.
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(letterbox.toArgb())
        }
    }
    val unavailable = state.problem == CameraProblem.CAMERA_UNAVAILABLE
    // Where the capture animation starts (the preview) and lands (the page stack), in this screen's coordinates.
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    var stackBounds by remember { mutableStateOf<Rect?>(null) }

    // The camera starts once the settings are known: HD decides the photo size.
    if (state.settingsLoaded) {
        // One camera session per lifecycle owner, attempt and resolution: a released session is never started again.
        val camera = remember(lifecycleOwner, state.cameraSession, state.hd) {
            ScanCamera(context.applicationContext, FrameAnalyzer(viewModel.detector, viewModel::onFrame), hd = state.hd)
        }
        DisposableEffect(camera) {
            camera.start(
                owner = lifecycleOwner,
                previewView = previewView,
                onReady = viewModel::onCameraReady,
                onError = viewModel::onCameraError,
            )
            onDispose { camera.release() }
        }
        LaunchedEffect(camera, state.flash) { camera.setFlash(state.flash) }
        LaunchedEffect(camera) {
            viewModel.onScreenShown()
            viewModel.captureRequests.collect { viewModel.onPhoto(camera.takePicture()) }
        }
    }
    // A failed picture is reported for a moment, then the hint takes over again.
    LaunchedEffect(state.problem) {
        if (state.problem == CameraProblem.CAPTURE_FAILED || state.problem == CameraProblem.STORAGE_FAILED) {
            delay(PROBLEM_VISIBLE_MILLIS)
            viewModel.onProblemShown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { screenOrigin = it.positionInRoot() },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CameraTopBar(
                state = state,
                onClose = onClose,
                onFlash = viewModel::onFlashPressed,
                onGrid = viewModel::onGridToggled,
                onHd = viewModel::onHdToggled,
                onAutoCapture = viewModel::onAutoCaptureToggled,
                onAutoEnhance = viewModel::onAutoEnhanceToggled,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // The preview sits right under the top bar at the frame's own shape;
                // the spare height collects below it, where the hint lives.
                val previewDescription = stringResource(R.string.feature_scan_camera_preview_description)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .aspectRatio(state.frameAspect.coerceIn(MIN_FRAME_ASPECT, MAX_FRAME_ASPECT))
                        .onGloballyPositioned { previewBounds = it.boundsInRoot() },
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = previewDescription },
                    )
                    if (state.grid) GridOverlay(frameAspect = state.frameAspect, modifier = Modifier.fillMaxSize())
                    if (state.mode == CameraMode.ID_CARD) {
                        IdCardGuide(frameAspect = state.frameAspect, cardFound = state.outline != null, modifier = Modifier.fillMaxSize())
                    } else {
                        DocumentOutline(outline = state.outline, frameAspect = state.frameAspect, modifier = Modifier.fillMaxSize())
                    }
                    CaptureFlash(capturing = state.isCapturing, modifier = Modifier.fillMaxSize())
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        if (state.mode == CameraMode.ID_CARD && !unavailable) {
                            IdCardStep(side = state.idSide, front = state.idFront)
                        }
                        NoticePill(notice = state.notice, noticeKey = state.noticeKey, onShown = viewModel::onNoticeShown)
                    }
                }

                if (unavailable) {
                    CameraUnavailable(
                        hasPages = state.pageCount > 0,
                        onRetry = viewModel::onRetryCamera,
                        onClose = onClose,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(letterbox),
                    )
                } else {
                    GuidanceHint(
                        guidance = state.guidance(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(Spacing.md),
                    )
                    state.problem?.let { problem ->
                        MessageBanner(
                            text = stringResource(
                                when (problem) {
                                    CameraProblem.STORAGE_FAILED -> R.string.feature_scan_camera_problem_storage
                                    else -> R.string.feature_scan_camera_problem_capture
                                },
                            ),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(Spacing.lg),
                        )
                    }
                }
            }

            ModeSwitcher(
                mode = state.mode,
                enabled = !state.isCapturing && !state.isRetake && !unavailable,
                onSelect = viewModel::onModeSelected,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = Spacing.sm),
            )

            CameraBottomBar(
                state = state,
                unavailable = unavailable,
                onShutter = viewModel::onShutterPressed,
                onUndo = viewModel::onUndo,
                onReview = onReview,
                onStackPositioned = { stackBounds = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            )
        }

        FlyingPageOverlay(
            flying = state.flying,
            from = previewBounds?.translate(-screenOrigin),
            to = stackBounds?.translate(-screenOrigin),
            onDone = viewModel::onFlyingPageShown,
        )
    }
}

/** Page stack and undo on the left, the shutter in the middle, Done on the right. */
@Composable
private fun CameraBottomBar(
    state: CameraUiState,
    unavailable: Boolean,
    onShutter: () -> Unit,
    onUndo: () -> Unit,
    onReview: () -> Unit,
    onStackPositioned: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val waitingForBack = state.mode == CameraMode.ID_CARD && state.idSide == IdSide.BACK
    val canUndo = !state.isRetake && (waitingForBack || state.capturedThisVisit > 0)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // The plain (scope-free) overload: this sits in a Row inside a Row.
            androidx.compose.animation.AnimatedVisibility(
                visible = state.pageCount > 0,
                enter = fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.8f),
                exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.8f),
            ) {
                PageStack(
                    count = state.pageCount,
                    thumbnail = state.lastThumbnail,
                    onClick = onReview,
                    modifier = Modifier.onGloballyPositioned { onStackPositioned(it.boundsInRoot()) },
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = canUndo,
                enter = fadeIn(Motion.enter()),
                exit = fadeOut(Motion.exit()),
            ) {
                IconButton(onClick = onUndo, enabled = !state.isCapturing) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = stringResource(
                            if (waitingForBack) R.string.feature_scan_camera_retake_front else R.string.feature_scan_camera_undo,
                        ),
                    )
                }
            }
        }
        ShutterButton(
            steadyProgress = state.steadyProgress,
            showProgress = state.autoCapture && state.outline != null,
            busy = state.isCapturing,
            enabled = !unavailable,
            onClick = onShutter,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            androidx.compose.animation.AnimatedVisibility(
                visible = state.pageCount > 0 && !state.isRetake && !waitingForBack,
                enter = fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.9f),
                exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 0.9f),
            ) {
                FilledTonalButton(
                    onClick = onReview,
                    enabled = !state.isCapturing,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.feature_scan_camera_done))
                }
            }
        }
    }
}

/** Shown in place of the preview when the camera could not be opened. */
@Composable
private fun CameraUnavailable(
    hasPages: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredMessage(
        icon = Icons.Rounded.NoPhotography,
        iconTint = MaterialTheme.colorScheme.error,
        title = stringResource(R.string.feature_scan_camera_unavailable_title),
        body = stringResource(R.string.feature_scan_camera_problem_unavailable),
        modifier = modifier,
    ) {
        PrimaryButton(text = stringResource(R.string.feature_scan_camera_try_again), onClick = onRetry)
        QuietButton(
            text = stringResource(
                if (hasPages) R.string.feature_scan_camera_back_to_pages else R.string.feature_scan_camera_close,
            ),
            onClick = onClose,
        )
    }
}

@Composable
private fun PermissionScreen(
    permanentlyDenied: Boolean,
    hasPages: Boolean,
    onAllow: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.padding(Spacing.xs)) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(
                    if (hasPages) R.string.feature_scan_camera_back_to_pages else R.string.feature_scan_camera_close,
                ),
            )
        }
        CenteredMessage(
            icon = Icons.Rounded.PhotoCamera,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.feature_scan_permission_title),
            body = stringResource(
                if (permanentlyDenied) R.string.feature_scan_permission_body_denied else R.string.feature_scan_permission_body,
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.xs),
                )
                Text(
                    text = stringResource(R.string.feature_scan_permission_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (permanentlyDenied) {
                PrimaryButton(
                    text = stringResource(R.string.feature_scan_permission_settings),
                    onClick = { context.openAppSettings() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PrimaryButton(
                    text = stringResource(R.string.feature_scan_permission_allow),
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            QuietButton(
                text = stringResource(R.string.feature_scan_permission_not_now),
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Icon, heading, explanation and actions, centred: the camera's full-area states. */
@Composable
private fun CenteredMessage(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconTile(icon = icon, tint = iconTint, size = MESSAGE_ICON_SIZE)
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MESSAGE_MAX_WIDTH),
        )
        Spacer(Modifier.height(Spacing.xl))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            actions()
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private val MESSAGE_ICON_SIZE = 64.dp
private val MESSAGE_MAX_WIDTH = 360.dp
private const val PROBLEM_VISIBLE_MILLIS = 4_000L
private const val MIN_FRAME_ASPECT = 0.3f
private const val MAX_FRAME_ASPECT = 3f
