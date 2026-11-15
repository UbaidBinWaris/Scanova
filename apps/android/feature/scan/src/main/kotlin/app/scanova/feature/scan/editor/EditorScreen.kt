package app.scanova.feature.scan.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.PhotoFilter
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.render.look
import app.scanova.feature.scan.ui.CropCanvas
import app.scanova.feature.scan.ui.EditingTab
import app.scanova.feature.scan.ui.EditingTabs
import app.scanova.feature.scan.ui.LookPanel
import app.scanova.feature.scan.ui.PageThumbStrip
import app.scanova.feature.scan.ui.StripPage
import kotlinx.coroutines.delay

/**
 * The page editor for a saved document: filters and adjustments, markup, and
 * page tools (crop, turn, reorder, delete). Nothing changes in the document
 * until Done; Cancel with changes asks first.
 *
 * @param onDone called when the editor closes, saved or not.
 */
@Composable
internal fun EditorRoute(onDone: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }
    BackHandler(enabled = !state.saving && !state.done, onBack = viewModel::onCancel)

    val cropping = state.crop != null
    Scaffold(
        topBar = {
            EditorTopBar(
                title = stringResource(if (cropping) R.string.feature_scan_crop_title else R.string.feature_scan_editor_title),
                saving = state.saving,
                saveProgress = state.saveProgress,
                cropping = cropping,
                enabled = !state.loading && state.pages.isNotEmpty(),
                onBack = viewModel::onCancel,
                onDone = if (cropping) viewModel::onCropApply else viewModel::onDone,
            )
        },
        bottomBar = {
            if (!state.loading && state.pages.isNotEmpty()) {
                if (cropping) {
                    CropBar(
                        detecting = state.crop?.detecting == true,
                        onAutoDetect = viewModel::onCropAutoDetect,
                        onWholePage = viewModel::onCropWholePage,
                    )
                } else {
                    EditingTabs(
                        tabs = EDITOR_TABS,
                        selected = state.tab.ordinal,
                        enabled = !state.saving,
                        onSelect = { viewModel.onTabSelected(EditorTab.entries[it]) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
        when {
            state.loading -> LoadingState(modifier = modifier, message = stringResource(R.string.feature_scan_editor_loading))
            state.pages.isEmpty() -> ErrorState(
                message = stringResource(R.string.feature_scan_editor_load_failed),
                retryLabel = stringResource(R.string.feature_scan_crop_go_back),
                onRetry = onDone,
                modifier = modifier,
            )
            cropping -> CropContent(state = state, viewModel = viewModel, modifier = modifier)
            else -> EditorContent(state = state, viewModel = viewModel, modifier = modifier)
        }
    }

    if (state.confirmingDiscard) {
        AlertDialog(
            onDismissRequest = viewModel::onDiscardDismissed,
            title = { Text(stringResource(R.string.feature_scan_editor_discard_title)) },
            text = { Text(stringResource(R.string.feature_scan_editor_discard_body)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_scan_editor_discard_confirm),
                    onClick = viewModel::onDiscardConfirmed,
                    destructive = true,
                )
            },
            dismissButton = {
                QuietButton(text = stringResource(R.string.feature_scan_editor_keep_editing), onClick = viewModel::onDiscardDismissed)
            },
        )
    }

    state.confirmingDeleteId?.let { pageId ->
        val number = state.pages.indexOfFirst { it.id == pageId } + 1
        AlertDialog(
            onDismissRequest = viewModel::onDeleteDismissed,
            title = { Text(stringResource(R.string.feature_scan_review_delete_title, number)) },
            text = { Text(stringResource(R.string.feature_scan_editor_delete_body)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_scan_review_delete_confirm),
                    onClick = viewModel::onDeleteConfirmed,
                    destructive = true,
                )
            },
            dismissButton = {
                QuietButton(text = stringResource(R.string.feature_scan_review_delete_cancel), onClick = viewModel::onDeleteDismissed)
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    saving: Boolean,
    saveProgress: Float,
    cropping: Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val progress by animateFloatAsState(saveProgress, Motion.quick(), label = "saveProgress")
    Column {
        ScanovaTopBar(
            title = title,
            onBack = if (saving) null else onBack,
            actions = {
                PrimaryButton(
                    text = stringResource(
                        when {
                            saving -> R.string.feature_scan_editor_saving
                            cropping -> R.string.feature_scan_editor_crop_apply
                            else -> R.string.feature_scan_editor_done
                        },
                    ),
                    onClick = onDone,
                    loading = saving,
                    enabled = enabled,
                    icon = Icons.Outlined.Check,
                    modifier = Modifier.padding(end = Spacing.sm),
                )
            },
        )
        AnimatedVisibility(visible = saving, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EditorContent(state: EditorUiState, viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val selected = state.selected ?: return
    val number = state.selectedIndex + 1
    val total = state.pages.size
    val editable = !state.saving
    // A preview that could not be made is mentioned for a moment; save problems stay until the next try.
    LaunchedEffect(state.problem) {
        if (state.problem == EditorProblem.PREVIEW_FAILED) {
            delay(PROBLEM_VISIBLE_MILLIS)
            viewModel.onProblemShown()
        }
    }
    Column(modifier = modifier) {
        ProblemBanner(problem = state.problem)
        EditorPager(
            state = state,
            onPageSelected = viewModel::onPageSelected,
            onPreviewNeeded = viewModel::onPreviewNeeded,
            onStroke = viewModel::onStroke,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = Spacing.sm),
        )
        PageThumbStrip(
            pages = state.pages.map { StripPage(it.id, it.thumbnail, it.id in state.rendering) },
            selectedIndex = state.selectedIndex,
            enabled = editable,
            onSelect = viewModel::onPageSelected,
            onMove = viewModel::onMovePage,
            modifier = Modifier.padding(vertical = Spacing.xs),
        )
        AnimatedContent(
            targetState = state.tab,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            label = "editorTab",
        ) { tab ->
            when (tab) {
                EditorTab.IMAGES -> LookPanel(
                    look = selected.edit.look,
                    thumbnails = state.live.thumbnails.takeIf { state.live.layers?.pageId == selected.id }.orEmpty(),
                    mode = state.lookMode,
                    enabled = editable,
                    canApplyToAll = total > 1,
                    onModeChange = viewModel::onLookModeChange,
                    onFilterSelected = viewModel::onFilterSelected,
                    onLookChange = viewModel::onLookChange,
                    onLookChangeFinished = viewModel::onLookChangeFinished,
                    onApplyToAll = viewModel::onApplyLookToAll,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )
                EditorTab.MARKUP -> MarkupTools(
                    tool = state.markupTool,
                    colorIndex = state.markupColor,
                    widthIndex = state.markupWidth,
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    canClear = selected.edit.markup.isNotEmpty(),
                    enabled = editable,
                    onTool = viewModel::onMarkupToolSelected,
                    onColor = viewModel::onMarkupColorSelected,
                    onWidth = viewModel::onMarkupWidthSelected,
                    onUndo = viewModel::onUndo,
                    onRedo = viewModel::onRedo,
                    onClear = viewModel::onClearMarkup,
                )
                EditorTab.PAGE -> EditorPageTools(
                    number = number,
                    total = total,
                    hasOriginal = selected.source.hasOriginal,
                    enabled = editable,
                    onCrop = viewModel::onCropStart,
                    onRotateLeft = { viewModel.onRotate(-1) },
                    onRotateRight = { viewModel.onRotate(1) },
                    onMoveEarlier = { viewModel.onMovePage(selected.id, -1) },
                    onMoveLater = { viewModel.onMovePage(selected.id, 1) },
                    onDelete = { viewModel.onDeleteRequested(selected.id) },
                )
            }
        }
    }
}

@Composable
private fun ProblemBanner(problem: EditorProblem?) {
    AnimatedVisibility(
        visible = problem != null,
        enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()),
        exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit()),
    ) {
        MessageBanner(
            text = stringResource(
                when (problem) {
                    EditorProblem.RENDER_FAILED -> R.string.feature_scan_editor_render_failed
                    EditorProblem.PREVIEW_FAILED -> R.string.feature_scan_editor_preview_failed
                    EditorProblem.LOAD_FAILED -> R.string.feature_scan_editor_load_failed
                    else -> R.string.feature_scan_editor_save_failed
                },
            ),
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
    }
}

/** The page's original, upright, with its crop outline to drag. */
@Composable
private fun CropContent(state: EditorUiState, viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val crop = state.crop ?: return
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.feature_scan_crop_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
        )
        AnimatedVisibility(
            visible = crop.nothingFound,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit()),
        ) {
            MessageBanner(
                text = stringResource(R.string.feature_scan_crop_nothing_detected),
                tone = BannerTone.Info,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            CropCanvas(
                photo = crop.image,
                outline = crop.outline,
                enabled = !crop.detecting,
                onCornerMoved = viewModel::onCropCornerMoved,
                onCornerNudged = viewModel::onCropCornerNudged,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CropBar(detecting: Boolean, onAutoDetect: () -> Unit, onWholePage: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SecondaryButton(
                text = stringResource(R.string.feature_scan_crop_auto_detect),
                onClick = onAutoDetect,
                enabled = !detecting,
                icon = Icons.Outlined.AutoFixHigh,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = stringResource(R.string.feature_scan_crop_whole_photo),
                onClick = onWholePage,
                enabled = !detecting,
                icon = Icons.Outlined.Fullscreen,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val EDITOR_TABS = listOf(
    EditingTab(R.string.feature_scan_tab_images, Icons.Outlined.PhotoFilter),
    EditingTab(R.string.feature_scan_tab_markup, Icons.Outlined.Draw),
    EditingTab(R.string.feature_scan_tab_page, Icons.Outlined.Description),
)

private const val PROBLEM_VISIBLE_MILLIS = 4_000L
