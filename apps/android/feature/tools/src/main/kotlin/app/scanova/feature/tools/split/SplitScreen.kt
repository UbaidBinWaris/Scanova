package app.scanova.feature.tools.split

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.BottomActions
import app.scanova.feature.tools.common.CreatedDocumentsPanel
import app.scanova.feature.tools.common.DocumentPicker
import app.scanova.feature.tools.common.WorkingPanel
import app.scanova.feature.tools.common.message

@Composable
internal fun SplitRoute(
    onClose: () -> Unit,
    onScan: () -> Unit,
    onOpenDocument: (String) -> Unit,
    viewModel: SplitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState by viewModel.list.state.collectAsStateWithLifecycle()
    val query by viewModel.list.query.collectAsStateWithLifecycle()

    LaunchedEffect(state.openId) {
        val id = state.openId ?: return@LaunchedEffect
        viewModel.onOpenHandled()
        onOpenDocument(id)
    }
    BackHandler(enabled = state.working != null || (state.step == SplitStep.PAGES && state.created == null)) {
        viewModel.onBack()
    }

    val working = state.working
    val created = state.created
    val choosing = state.step == SplitStep.PAGES && working == null && created == null
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScanovaTopBar(
                title = stringResource(if (state.step == SplitStep.PICK) R.string.feature_tools_split_title else R.string.feature_tools_split_pages_title),
                onBack = { if (!viewModel.onBack()) onClose() },
                actions = {
                    if (choosing && state.mode == SplitMode.SELECTED && state.pageCount > 0) {
                        val all = state.selected.size == state.pageCount
                        IconButton(onClick = if (all) viewModel::onClearSelection else viewModel::onSelectAll) {
                            Icon(
                                imageVector = if (all) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                                contentDescription = stringResource(
                                    if (all) R.string.feature_tools_split_clear_selection else R.string.feature_tools_split_select_all,
                                ),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (choosing && state.pages is PagesState.Ready) {
                val parts = state.plan.size
                BottomActions(
                    above = {
                        Text(
                            text = if (parts == 0) {
                                stringResource(R.string.feature_tools_split_nothing_yet)
                            } else {
                                pluralStringResource(R.plurals.feature_tools_split_summary, parts, parts)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.feature_tools_split_action),
                        onClick = viewModel::onSplit,
                        icon = Icons.Outlined.ContentCut,
                        enabled = parts > 0,
                        modifier = Modifier.weight(1f),
                    )
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
                    title = stringResource(R.string.feature_tools_split_working),
                    detail = if (working.total > 0) stringResource(R.string.feature_tools_progress_pages, working.done, working.total) else null,
                    progress = working,
                    onCancel = viewModel::onStop,
                )
                created != null -> CreatedDocumentsPanel(
                    documents = created,
                    skipped = emptyList(),
                    onOpen = onOpenDocument,
                    onDone = onClose,
                )
                state.step == SplitStep.PICK -> DocumentPicker(
                    state = listState,
                    query = query,
                    onQueryChange = viewModel.list::onQueryChange,
                    onDocumentClick = viewModel::onPick,
                    loadCover = viewModel.covers::cover,
                    onScan = onScan,
                    onRetry = viewModel.list::onRetry,
                    contentPadding = PaddingValues(Spacing.lg),
                    header = {
                        item(key = "hint") {
                            Text(
                                text = stringResource(R.string.feature_tools_split_pick_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                            )
                        }
                    },
                )
                else -> when (val pages = state.pages) {
                    PagesState.Loading -> LoadingState(message = stringResource(R.string.feature_tools_split_loading_pages))
                    is PagesState.Failed -> ErrorState(message = pages.error.message(), onRetry = viewModel::onRetryPages)
                    is PagesState.Ready -> PageChooser(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun PageChooser(state: SplitUiState, viewModel: SplitViewModel) {
    val includedInRanges = remember(state.mode, state.ranges, state.pageCount) {
        if (state.mode == SplitMode.RANGES) state.plan.flatten().toSet() else emptySet()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = PageCellMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                state.document?.let { document ->
                    Text(
                        text = document.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ModeSelector(state.mode, viewModel::onMode)
                Text(
                    text = stringResource(state.mode.hint()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.mode == SplitMode.RANGES) {
                    ScanovaTextField(
                        value = state.ranges,
                        onValueChange = viewModel::onRangesChange,
                        label = stringResource(R.string.feature_tools_split_ranges_label),
                        supportingText = stringResource(R.string.feature_tools_split_ranges_example, state.pageCount),
                        errorText = state.rangeError?.let { rangeErrorText(it, state.pageCount) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.error?.let { MessageBanner(text = it.message()) }
                MessageBanner(text = stringResource(R.string.feature_tools_split_note), tone = BannerTone.Info)
            }
        }
        items(List(state.pageCount) { it }, key = { it }) { index ->
            PageCell(
                index = index,
                mode = state.mode,
                selected = when (state.mode) {
                    SplitMode.SELECTED -> index in state.selected
                    SplitMode.EACH_PAGE -> true
                    SplitMode.RANGES -> index in includedInRanges
                },
                loadThumbnail = viewModel::thumbnail,
                onToggle = { viewModel.onTogglePage(index) },
            )
        }
    }
}

@Composable
private fun ModeSelector(mode: SplitMode, onMode: (SplitMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SplitMode.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onMode(option) },
                shape = SegmentedButtonDefaults.itemShape(index, SplitMode.entries.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(option.label()), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PageCell(
    index: Int,
    mode: SplitMode,
    selected: Boolean,
    loadThumbnail: suspend (Int) -> Bitmap?,
    onToggle: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = Motion.quick(),
        label = "pageBorder",
    )
    val pageLabel = stringResource(R.string.feature_tools_split_page_label, index + 1)
    val interaction = remember { MutableInteractionSource() }
    val action = if (mode == SplitMode.SELECTED) {
        Modifier.toggleable(
            value = selected,
            interactionSource = interaction,
            indication = ripple(),
            role = Role.Checkbox,
            onValueChange = { onToggle() },
        )
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .clip(shape)
            .then(action)
            .semantics(mergeDescendants = true) { contentDescription = pageLabel }
            .padding(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAGE_ASPECT),
        ) {
            Box {
                val thumbnail by produceState<Bitmap?>(initialValue = null, index) { value = loadThumbnail(index) }
                Crossfade(targetState = thumbnail, animationSpec = Motion.quick(), label = "pageThumbnail") { bitmap ->
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.xs),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (mode == SplitMode.SELECTED) {
                    CheckMark(
                        checked = selected,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(Spacing.xs),
                    )
                }
            }
        }
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** A tick on a disc when checked, an empty ring otherwise. Decorative: the cell carries the checkbox state. */
@Composable
private fun CheckMark(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (checked) MaterialTheme.colorScheme.surface else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}

private fun SplitMode.label(): Int = when (this) {
    SplitMode.SELECTED -> R.string.feature_tools_split_mode_selected
    SplitMode.EACH_PAGE -> R.string.feature_tools_split_mode_each
    SplitMode.RANGES -> R.string.feature_tools_split_mode_ranges
}

private fun SplitMode.hint(): Int = when (this) {
    SplitMode.SELECTED -> R.string.feature_tools_split_mode_selected_hint
    SplitMode.EACH_PAGE -> R.string.feature_tools_split_mode_each_hint
    SplitMode.RANGES -> R.string.feature_tools_split_mode_ranges_hint
}

@Composable
private fun rangeErrorText(error: RangeError, pageCount: Int): String = when (error) {
    RangeError.EMPTY -> stringResource(R.string.feature_tools_split_ranges_error_empty)
    RangeError.SYNTAX -> stringResource(R.string.feature_tools_split_ranges_error_syntax)
    RangeError.OUT_OF_BOUNDS -> stringResource(R.string.feature_tools_split_ranges_error_bounds, pageCount)
    RangeError.REVERSED -> stringResource(R.string.feature_tools_split_ranges_error_reversed)
}

private const val PAGE_ASPECT = 0.75f
private val PageCellMinWidth = 96.dp
