package app.scanova.feature.scan.review

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R

@Composable
internal fun ReviewRoute(
    onAddPage: () -> Unit,
    onAdjustPage: (pageId: String) -> Unit,
    onSaved: (documentId: String) -> Unit,
    onDiscarded: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedDocumentId) {
        state.savedDocumentId?.let(onSaved)
    }
    LaunchedEffect(state.discarded) {
        if (state.discarded) onDiscarded()
    }
    val leave = { if (!state.isSaving) viewModel.onDiscardRequested() }
    BackHandler(enabled = !state.isSaving, onBack = leave)

    Scaffold(
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_scan_review_title), onBack = leave) },
        bottomBar = {
            if (state.pages.isNotEmpty()) SaveBar(state = state, onSave = viewModel::onSave)
        },
    ) { padding ->
        if (state.pages.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.DocumentScanner,
                title = stringResource(R.string.feature_scan_review_empty_title),
                body = stringResource(R.string.feature_scan_review_empty_body),
                actionLabel = stringResource(R.string.feature_scan_review_scan_page),
                actionIcon = Icons.Outlined.CameraAlt,
                onAction = onAddPage,
                modifier = Modifier.padding(padding),
            )
        } else {
            ReviewContent(
                state = state,
                viewModel = viewModel,
                onAddPage = onAddPage,
                onAdjustPage = onAdjustPage,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            )
        }
    }

    if (state.confirmingDiscard) {
        AlertDialog(
            onDismissRequest = viewModel::onDiscardDismissed,
            title = { Text(stringResource(R.string.feature_scan_review_discard_title)) },
            text = { Text(stringResource(R.string.feature_scan_review_discard_body)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_scan_review_discard_confirm),
                    onClick = viewModel::onDiscardConfirmed,
                    destructive = true,
                )
            },
            dismissButton = {
                QuietButton(
                    text = stringResource(R.string.feature_scan_review_keep),
                    onClick = viewModel::onDiscardDismissed,
                )
            },
        )
    }

    state.confirmingDeleteId?.let { pageId ->
        val number = state.pages.indexOfFirst { it.id == pageId } + 1
        AlertDialog(
            onDismissRequest = viewModel::onDeleteDismissed,
            title = { Text(stringResource(R.string.feature_scan_review_delete_title, number)) },
            text = { Text(stringResource(R.string.feature_scan_review_delete_body)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_scan_review_delete_confirm),
                    onClick = viewModel::onDeleteConfirmed,
                    destructive = true,
                )
            },
            dismissButton = {
                QuietButton(
                    text = stringResource(R.string.feature_scan_review_delete_cancel),
                    onClick = viewModel::onDeleteDismissed,
                )
            },
        )
    }
}

@Composable
private fun ReviewContent(
    state: ReviewUiState,
    viewModel: ReviewViewModel,
    onAddPage: () -> Unit,
    onAdjustPage: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val selected = state.pages[state.selectedIndex]
    val number = state.selectedIndex + 1
    val total = state.pages.size
    val editable = !state.isSaving
    val selectedChanging = selected.id in state.changingLook

    Column(modifier = modifier) {
        ScanovaTextField(
            value = state.title,
            onValueChange = viewModel::onTitleChange,
            label = stringResource(R.string.feature_scan_review_title_label),
            enabled = editable,
            errorText = if (state.titleMissing) stringResource(R.string.feature_scan_review_title_required) else null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
        )
        Spacer(Modifier.height(Spacing.md))

        PagePager(
            state = state,
            onPageSelected = viewModel::onPageSelected,
            onPreviewNeeded = viewModel::onPreviewNeeded,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))

        PageTools(
            number = number,
            total = total,
            enabled = editable && !selectedChanging,
            onMoveEarlier = { viewModel.onMovePage(selected.id, -1) },
            onMoveLater = { viewModel.onMovePage(selected.id, 1) },
            onCrop = { onAdjustPage(selected.id) },
            onRetake = {
                viewModel.onRetakePage(selected.id)
                onAddPage()
            },
            onDelete = { viewModel.onDeleteRequested(selected.id) },
        )
        LookChips(
            selected = state.lookOf(selected),
            enabled = editable,
            onSelect = { filter -> viewModel.onLookSelected(selected.id, filter) },
        )
        Spacer(Modifier.height(Spacing.sm))
        PageStrip(
            pages = state.pages,
            selectedIndex = state.selectedIndex,
            enabled = editable,
            onSelect = viewModel::onPageSelected,
            onMove = viewModel::onMovePage,
            onAddPage = onAddPage,
        )
        Spacer(Modifier.height(Spacing.sm))
    }
}

/** The one primary action, with its progress and any problem right above it. */
@Composable
private fun SaveBar(state: ReviewUiState, onSave: () -> Unit) {
    val progress by animateFloatAsState(state.saveProgress, tween(MOTION_MILLIS), label = "saveProgress")
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AnimatedVisibility(
                visible = state.problem != null,
                enter = fadeIn(tween(MOTION_MILLIS)) + expandVertically(tween(MOTION_MILLIS)),
                exit = fadeOut(tween(MOTION_MILLIS)) + shrinkVertically(tween(MOTION_MILLIS)),
            ) {
                MessageBanner(
                    text = stringResource(
                        when (state.problem) {
                            ReviewProblem.LOOK_FAILED -> R.string.feature_scan_review_look_failed
                            else -> R.string.feature_scan_review_save_failed
                        },
                    ),
                )
            }
            AnimatedVisibility(
                visible = state.isSaving,
                enter = fadeIn(tween(MOTION_MILLIS)),
                exit = fadeOut(tween(MOTION_MILLIS)),
            ) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            PrimaryButton(
                text = stringResource(
                    if (state.isSaving) R.string.feature_scan_review_saving else R.string.feature_scan_review_save,
                ),
                onClick = onSave,
                loading = state.isSaving,
                enabled = state.changingLook.isEmpty(),
                icon = Icons.Outlined.Save,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal const val MOTION_MILLIS = 200
