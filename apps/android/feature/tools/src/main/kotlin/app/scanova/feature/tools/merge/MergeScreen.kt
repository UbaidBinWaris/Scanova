package app.scanova.feature.tools.merge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.data.model.Document
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SectionHeader
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.BottomActions
import app.scanova.feature.tools.common.DocumentPicker
import app.scanova.feature.tools.common.DocumentThumbnail
import app.scanova.feature.tools.common.SelectionMark
import app.scanova.feature.tools.common.ThumbnailHeight
import app.scanova.feature.tools.common.ThumbnailWidth
import app.scanova.feature.tools.common.WorkingPanel
import app.scanova.feature.tools.common.documentDetails
import app.scanova.feature.tools.common.message

@Composable
internal fun MergeRoute(
    onClose: () -> Unit,
    onScan: () -> Unit,
    onMerged: (String) -> Unit,
    viewModel: MergeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState by viewModel.list.state.collectAsStateWithLifecycle()
    val query by viewModel.list.query.collectAsStateWithLifecycle()

    LaunchedEffect(state.mergedId) {
        val id = state.mergedId ?: return@LaunchedEffect
        viewModel.onMergedHandled()
        onMerged(id)
    }
    BackHandler(enabled = state.step == MergeStep.ARRANGE || state.working != null) { viewModel.onBack() }

    val working = state.working
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScanovaTopBar(
                title = stringResource(if (state.step == MergeStep.PICK) R.string.feature_tools_merge_title else R.string.feature_tools_merge_arrange_title),
                onBack = { if (!viewModel.onBack()) onClose() },
            )
        },
        bottomBar = {
            if (working == null) {
                when (state.step) {
                    MergeStep.PICK -> BottomActions(
                        above = {
                            Text(
                                text = if (state.selection.isEmpty()) {
                                    stringResource(R.string.feature_tools_merge_pick_hint)
                                } else {
                                    pluralStringResource(R.plurals.feature_tools_merge_selected, state.selection.size, state.selection.size)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.feature_tools_action_continue),
                            onClick = viewModel::onContinue,
                            enabled = state.canContinue,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    MergeStep.ARRANGE -> BottomActions {
                        PrimaryButton(
                            text = pluralStringResource(R.plurals.feature_tools_merge_action, state.selection.size, state.selection.size),
                            onClick = viewModel::onMerge,
                            icon = Icons.AutoMirrored.Outlined.CallMerge,
                            enabled = state.canContinue && state.title.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                working != null -> WorkingPanel(
                    title = stringResource(R.string.feature_tools_merge_working),
                    detail = if (working.total > 0) {
                        stringResource(R.string.feature_tools_progress_pages, working.done, working.total)
                    } else {
                        null
                    },
                    progress = working,
                    onCancel = viewModel::onStop,
                )
                state.step == MergeStep.PICK -> DocumentPicker(
                    state = listState,
                    query = query,
                    onQueryChange = viewModel.list::onQueryChange,
                    onDocumentClick = viewModel::onToggle,
                    loadCover = viewModel.covers::cover,
                    onScan = onScan,
                    onRetry = viewModel.list::onRetry,
                    selection = state.selection.map { it.id },
                    contentPadding = PaddingValues(Spacing.lg),
                )
                else -> ArrangeList(
                    state = state,
                    loadCover = viewModel.covers::cover,
                    onTitleChange = viewModel::onTitleChange,
                    onMove = viewModel::onMove,
                    onRemove = viewModel::onRemove,
                )
            }
        }
    }
}

@Composable
private fun ArrangeList(
    state: MergeUiState,
    loadCover: suspend (String) -> android.graphics.Bitmap?,
    onTitleChange: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "title") {
            ScanovaTextField(
                value = state.title,
                onValueChange = onTitleChange,
                label = stringResource(R.string.feature_tools_merge_title_label),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let { error ->
            item(key = "error") { MessageBanner(text = error.message()) }
        }
        item(key = "order_header") {
            SectionHeader(
                text = stringResource(R.string.feature_tools_merge_order),
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        itemsIndexed(state.selection, key = { _, document -> document.id }) { index, document ->
            ArrangeRow(
                document = document,
                position = index,
                count = state.selection.size,
                loadCover = loadCover,
                onMove = onMove,
                onRemove = { onRemove(document.id) },
                modifier = Modifier.animateItem(),
            )
        }
        item(key = "note") {
            MessageBanner(
                text = stringResource(R.string.feature_tools_merge_note),
                tone = BannerTone.Info,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
    }
}

@Composable
private fun ArrangeRow(
    document: Document,
    position: Int,
    count: Int,
    loadCover: suspend (String) -> android.graphics.Bitmap?,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScanovaCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = Spacing.md, top = Spacing.sm, bottom = Spacing.sm, end = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(contentAlignment = Alignment.BottomEnd) {
                DocumentThumbnail(
                    documentId = document.id,
                    loadCover = loadCover,
                    modifier = Modifier
                        .padding(end = Spacing.sm, bottom = Spacing.sm)
                        .size(width = ThumbnailWidth, height = ThumbnailHeight),
                )
                SelectionMark(order = position + 1)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = documentDetails(document),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Buttons, not drag and drop, so the order can be changed with any input method.
                Column {
                    IconButton(onClick = { onMove(position, position - 1) }, enabled = position > 0) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = stringResource(R.string.feature_tools_merge_move_up, document.title),
                        )
                    }
                    IconButton(onClick = { onMove(position, position + 1) }, enabled = position < count - 1) {
                        Icon(
                            Icons.Outlined.ArrowDownward,
                            contentDescription = stringResource(R.string.feature_tools_merge_move_down, document.title),
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.feature_tools_merge_remove, document.title),
                    )
                }
            }
        }
    }
}
