package app.scanova.feature.scan.crop

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.ui.CropCanvas

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
    val abandon = onBack
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
                    icon = Icons.Rounded.AutoFixHigh,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = stringResource(R.string.feature_scan_crop_whole_photo),
                    onClick = onUseWholePhoto,
                    enabled = !busy,
                    icon = Icons.Rounded.Fullscreen,
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
                icon = Icons.Rounded.Check,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val MOTION_MILLIS = 200
