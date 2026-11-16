package app.scanova.feature.scan.review

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R
import app.scanova.feature.scan.ui.EditingTab
import app.scanova.feature.scan.ui.EditingTabs
import app.scanova.feature.scan.ui.LookPanel
import app.scanova.feature.scan.ui.PageThumbStrip
import app.scanova.feature.scan.ui.StripPage

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
        topBar = {
            ReviewTopBar(
                title = state.title,
                saving = state.isSaving,
                saveProgress = state.saveProgress,
                canSave = state.pages.isNotEmpty(),
                onBack = leave,
                onRename = viewModel::onEditTitle,
                onSave = viewModel::onSave,
            )
        },
        bottomBar = {
            if (state.pages.isNotEmpty()) {
                EditingTabs(
                    tabs = REVIEW_TABS,
                    selected = state.tab.ordinal,
                    enabled = !state.isSaving,
                    onSelect = { viewModel.onTabSelected(ReviewTab.entries[it]) },
                )
            }
        },
    ) { padding ->
        if (state.pages.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.DocumentScanner,
                title = stringResource(R.string.feature_scan_review_empty_title),
                body = stringResource(R.string.feature_scan_review_empty_body),
                actionLabel = stringResource(R.string.feature_scan_review_scan_page),
                actionIcon = Icons.Rounded.CameraAlt,
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
                    .consumeWindowInsets(padding),
            )
        }
    }

    if (state.editingTitle) {
        TitleDialog(
            initial = state.title,
            missing = state.titleMissing,
            onConfirm = viewModel::onTitleConfirmed,
            onDismiss = viewModel::onTitleDismissed,
        )
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

/**
 * Back, the document's title (tap to rename) and Save, the screen's one
 * primary action. A progress line runs under it while the PDF is written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewTopBar(
    title: String,
    saving: Boolean,
    saveProgress: Float,
    canSave: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
) {
    val progress by animateFloatAsState(saveProgress, Motion.quick(), label = "saveProgress")
    val renameDescription = stringResource(R.string.feature_scan_review_rename_description, title)
    Column {
        TopAppBar(
            title = {
                Row(
                    modifier = Modifier
                        .clickable(enabled = !saving, role = Role.Button, onClick = onRename)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = renameDescription },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .semantics { heading() },
                    )
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack, enabled = !saving) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.feature_scan_review_discard_description),
                    )
                }
            },
            actions = {
                PrimaryButton(
                    text = stringResource(if (saving) R.string.feature_scan_review_saving else R.string.feature_scan_review_save),
                    onClick = onSave,
                    loading = saving,
                    enabled = canSave,
                    icon = Icons.Rounded.Save,
                    modifier = Modifier.padding(end = Spacing.sm),
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        AnimatedVisibility(visible = saving, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
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
    val selected = state.selected ?: return
    val number = state.selectedIndex + 1
    val total = state.pages.size
    val editable = !state.isSaving

    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = state.problem != null,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit()),
        ) {
            MessageBanner(
                text = stringResource(
                    when (state.problem) {
                        ReviewProblem.PAGES_FAILED -> R.string.feature_scan_review_pages_failed
                        else -> R.string.feature_scan_review_save_failed
                    },
                ),
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }
        ReviewPager(
            state = state,
            onPageSelected = viewModel::onPageSelected,
            onPreviewNeeded = viewModel::onPreviewNeeded,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = Spacing.sm),
        )
        PageThumbStrip(
            pages = state.pages.map { StripPage(it.id, it.thumbnail, it.processing) },
            selectedIndex = state.selectedIndex,
            enabled = editable,
            onSelect = viewModel::onPageSelected,
            onMove = viewModel::onMovePage,
            onAddPage = onAddPage,
            modifier = Modifier.padding(vertical = Spacing.xs),
        )
        AnimatedContent(
            targetState = state.tab,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            label = "reviewTab",
        ) { tab ->
            when (tab) {
                ReviewTab.FILTERS -> LookPanel(
                    look = selected.look,
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
                ReviewTab.PAGE -> ReviewPageTools(
                    number = number,
                    total = total,
                    enabled = editable,
                    onCrop = { onAdjustPage(selected.id) },
                    onRotateLeft = { viewModel.onRotate(-1) },
                    onRotateRight = { viewModel.onRotate(1) },
                    onRetake = {
                        viewModel.onRetakePage(selected.id)
                        onAddPage()
                    },
                    onMoveEarlier = { viewModel.onMovePage(selected.id, -1) },
                    onMoveLater = { viewModel.onMovePage(selected.id, 1) },
                    onDelete = { viewModel.onDeleteRequested(selected.id) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
    }
}

@Composable
private fun TitleDialog(initial: String, missing: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_scan_review_rename_title)) },
        text = {
            ScanovaTextField(
                value = text,
                onValueChange = { text = it },
                label = stringResource(R.string.feature_scan_review_title_label),
                errorText = if (missing) stringResource(R.string.feature_scan_review_title_required) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = { QuietButton(text = stringResource(R.string.feature_scan_review_rename_confirm), onClick = { onConfirm(text) }) },
        dismissButton = { QuietButton(text = stringResource(R.string.feature_scan_review_rename_cancel), onClick = onDismiss) },
    )
}

private val REVIEW_TABS = listOf(
    EditingTab(R.string.feature_scan_tab_filters, Icons.Rounded.PhotoFilter),
    EditingTab(R.string.feature_scan_tab_page, Icons.Rounded.Description),
)
