package app.scanova.feature.scan.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.geometry.FrameMapping
import app.scanova.feature.scan.geometry.Quad

@Composable
internal fun CameraRoute(
    onPageCaptured: (pageId: String) -> Unit,
    onReview: () -> Unit,
    onClose: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionRequested = true
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state.capturedPageId) {
        state.capturedPageId?.let { pageId ->
            viewModel.onCapturedPageHandled()
            onPageCaptured(pageId)
        }
    }

    val leave = {
        viewModel.onLeaving()
        // With pages already scanned, "back" returns to them instead of dropping the scan.
        if (state.pageCount > 0) onReview() else onClose()
    }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            ScanovaTopBar(
                title = stringResource(
                    if (state.isRetake) R.string.feature_scan_camera_retake_title else R.string.feature_scan_camera_title,
                ),
                onBack = leave,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (hasPermission) {
                CameraContent(
                    state = state,
                    viewModel = viewModel,
                    onReview = {
                        viewModel.onLeaving()
                        onReview()
                    },
                )
            } else {
                PermissionExplanation(
                    offerSettings = permissionRequested,
                    onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        }
    }
}

@Composable
private fun CameraContent(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onReview: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            // The whole frame is shown (letterboxed), so the outline drawn on top
            // corresponds one-to-one to what the photo will contain.
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // One camera session per lifecycle owner: a released session is never started again.
    val camera = remember(lifecycleOwner) {
        ScanCamera(context.applicationContext, FrameAnalyzer(viewModel.detector, viewModel::onFrame))
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
    LaunchedEffect(state.torchOn) { camera.setTorch(state.torchOn) }
    LaunchedEffect(camera) {
        viewModel.onScreenShown()
        viewModel.captureRequests.collect { viewModel.onPhoto(camera.takePicture()) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val previewDescription = stringResource(R.string.feature_scan_camera_preview_description)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .semantics { contentDescription = previewDescription },
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            OutlineOverlay(
                outline = state.outline,
                frameAspect = state.frameAspect,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // What the outline shows, in words, for people who cannot see it.
                Text(
                    text = stringResource(
                        when {
                            state.isCapturing -> R.string.feature_scan_camera_hint_capturing
                            state.outline == null -> R.string.feature_scan_camera_hint_searching
                            state.autoCapture -> R.string.feature_scan_camera_hint_found_auto
                            else -> R.string.feature_scan_camera_hint_found_manual
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )

                state.problem?.let { problem ->
                    MessageBanner(
                        text = stringResource(
                            when (problem) {
                                CameraProblem.CAMERA_UNAVAILABLE -> R.string.feature_scan_camera_problem_unavailable
                                CameraProblem.CAPTURE_FAILED -> R.string.feature_scan_camera_problem_capture
                                CameraProblem.STORAGE_FAILED -> R.string.feature_scan_camera_problem_storage
                            },
                        ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FilterChip(
                        selected = state.autoCapture,
                        onClick = viewModel::onToggleAutoCapture,
                        label = { Text(stringResource(R.string.feature_scan_camera_auto_capture)) },
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    ShutterButton(
                        progress = state.steadyProgress,
                        busy = state.isCapturing,
                        onClick = viewModel::onShutterPressed,
                    )

                    Box(modifier = Modifier.weight(1f, fill = false), contentAlignment = Alignment.CenterEnd) {
                        if (state.torchAvailable) {
                            IconButton(onClick = viewModel::onToggleTorch) {
                                Icon(
                                    imageVector = if (state.torchOn) {
                                        Icons.Outlined.FlashlightOff
                                    } else {
                                        Icons.Outlined.FlashlightOn
                                    },
                                    contentDescription = stringResource(
                                        if (state.torchOn) {
                                            R.string.feature_scan_camera_torch_off
                                        } else {
                                            R.string.feature_scan_camera_torch_on
                                        },
                                    ),
                                )
                            }
                        } else {
                            Spacer(Modifier.size(48.dp))
                        }
                    }
                }

                if (state.pageCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.feature_scan_camera_page_count,
                                state.pageCount,
                                state.pageCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SecondaryButton(text = stringResource(R.string.feature_scan_camera_review), onClick = onReview)
                    }
                }
            }
        }
    }
}

/** The shutter: the screen's primary action. The ring fills while the page is held still. */
@Composable
private fun ShutterButton(progress: Float, busy: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.feature_scan_camera_take_picture)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
        CircularProgressIndicator(
            progress = { if (busy) 1f else progress },
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { },
            strokeWidth = 4.dp,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        FilledIconButton(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier
                .size(68.dp)
                .semantics { contentDescription = label },
            shape = CircleShape,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                // A plain disc: the universally recognised shutter shape.
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.onPrimary, CircleShape),
                )
            }
        }
    }
}

/** Draws the detected page outline over the letterboxed preview. */
@Composable
private fun OutlineOverlay(outline: Quad?, frameAspect: Float, modifier: Modifier = Modifier) {
    val stroke = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    Canvas(modifier = modifier) {
        if (outline == null || frameAspect <= 0f) return@Canvas
        val drawn = FrameMapping.fitInside(
            imageWidth = frameAspect.toDouble(),
            imageHeight = 1.0,
            viewWidth = size.width.toDouble(),
            viewHeight = size.height.toDouble(),
        )
        val points = outline.corners.map { corner ->
            val point = FrameMapping.toView(corner, drawn)
            Offset(point.x.toFloat(), point.y.toFloat())
        }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (index in 1 until points.size) lineTo(points[index].x, points[index].y)
            close()
        }
        drawPath(path, fill)
        drawPath(path, stroke, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun PermissionExplanation(offerSettings: Boolean, onAllow: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.feature_scan_permission_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.feature_scan_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))
        if (offerSettings) {
            PrimaryButton(
                text = stringResource(R.string.feature_scan_permission_settings),
                onClick = { context.openAppSettings() },
            )
        } else {
            PrimaryButton(text = stringResource(R.string.feature_scan_permission_allow), onClick = onAllow)
        }
    }
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}
