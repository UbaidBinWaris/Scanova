package app.scanova.feature.documents.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.common.result.AppError
import app.scanova.core.data.settings.LibraryLayout
import app.scanova.core.designsystem.R as DesignR
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.documents.R
import app.scanova.feature.documents.common.DocumentGridItem
import app.scanova.feature.documents.common.DocumentListItem
import app.scanova.feature.documents.common.EmptyLibrary
import app.scanova.feature.documents.common.GridCellMinWidth
import app.scanova.feature.documents.common.GridSkeleton
import app.scanova.feature.documents.common.ItemSelection
import app.scanova.feature.documents.common.ItemSpacing
import app.scanova.feature.documents.common.ListPadding
import app.scanova.feature.documents.common.ListSkeleton
import app.scanova.feature.documents.common.NoResults
import app.scanova.feature.documents.common.SearchTopBar
import app.scanova.feature.documents.common.deletionBodyRes
import app.scanova.feature.documents.common.rememberEntranceWindow
import app.scanova.feature.documents.common.staggeredEntrance

/** What the top of the Files tab shows. */
private enum class FilesBar { TITLE, SEARCH, SELECTION }

@OptIn(ExperimentalMaterial3Api::class) // Pull-to-refresh with an on/off switch.
@Composable
internal fun FilesRoute(
    onScan: () -> Unit,
    onImport: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onShareDocuments: (List<String>) -> Unit,
    onSaveDocuments: (List<String>) -> Unit = {},
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searching = searchOpen || state.query.isNotEmpty()
    val closeSearch = {
        searchOpen = false
        viewModel.onQueryChange("")
    }
    // Back first closes the selection, then the search, then leaves the tab.
    BackHandler(enabled = state.selectionMode, onBack = viewModel::onClearSelection)
    BackHandler(enabled = searching && !state.selectionMode, onBack = closeSearch)

    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = when (val message = state.message) {
        null -> null
        is FilesMessage.RefreshFailed -> message.error.message()
        is FilesMessage.DeleteFailed ->
            pluralStringResource(R.plurals.feature_documents_delete_failed, message.count, message.count)
    }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            viewModel.onMessageShown()
            snackbarHostState.showSnackbar(messageText)
        }
    }

    // Kept here, so the scroll position survives loading, searching and selecting.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    val bar = when {
        state.selectionMode -> FilesBar.SELECTION
        searching -> FilesBar.SEARCH
        else -> FilesBar.TITLE
    }
    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = bar,
                transitionSpec = { fadeIn(Motion.quick()) togetherWith fadeOut(Motion.quick()) },
                label = "filesBar",
            ) { mode ->
                when (mode) {
                    FilesBar.TITLE -> FilesTitleBar(
                        showDocumentActions = !state.isLoading && !state.loadFailed && !state.isEmptyLibrary,
                        layout = state.layout,
                        onOpenSearch = { searchOpen = true },
                        onToggleLayout = viewModel::onToggleLayout,
                    )
                    FilesBar.SEARCH -> SearchTopBar(
                        query = state.query,
                        onQueryChange = viewModel::onQueryChange,
                        onClose = closeSearch,
                    )
                    FilesBar.SELECTION -> SelectionBar(
                        count = state.selectedIds.size,
                        allSelected = state.allSelected,
                        enabled = !state.isDeleting,
                        onClose = viewModel::onClearSelection,
                        onToggleSelectAll = viewModel::onToggleSelectAll,
                        onShare = { onShareDocuments(viewModel.takeSelectionForSharing()) },
                        onSave = { onSaveDocuments(viewModel.takeSelectionForSharing()) },
                        onDelete = viewModel::onDeleteRequested,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val showList = !state.isLoading && !state.loadFailed && state.documents.isNotEmpty()
            if (showList) {
                ListHeader(
                    count = state.documents.size,
                    sort = state.sort,
                    enabled = !state.selectionMode,
                    onSortChange = viewModel::onSortChange,
                )
            }
            // Pulling fetches changes from the account; without one there is nothing to fetch. The
            // same Box either way, so turning pulling off (a selection opens) never resets the list.
            val refreshState = rememberPullToRefreshState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pullToRefresh(
                        isRefreshing = state.isRefreshing,
                        state = refreshState,
                        enabled = state.signedIn && !state.selectionMode,
                        onRefresh = viewModel::onRefresh,
                    ),
            ) {
                FilesContent(
                    state = state,
                    viewModel = viewModel,
                    listState = listState,
                    gridState = gridState,
                    onScan = onScan,
                    onImport = onImport,
                    onOpenDocument = onOpenDocument,
                    onClearSearch = { viewModel.onQueryChange("") },
                )
                // Over the list rather than above it, so nothing jumps while documents are deleted.
                if (state.isDeleting) {
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }
                if (state.signedIn) {
                    PullToRefreshDefaults.Indicator(
                        state = refreshState,
                        isRefreshing = state.isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    if (state.confirmingDelete) {
        DeleteDialog(
            count = state.selectedIds.size,
            reachesBackup = deletionReachesBackup(state.selectedDocuments, state.signedIn),
            onConfirm = viewModel::onDeleteConfirmed,
            onDismiss = viewModel::onDeleteDismissed,
        )
    }
}

@Composable
private fun FilesContent(
    state: FilesUiState,
    viewModel: FilesViewModel,
    listState: LazyListState,
    gridState: LazyGridState,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onClearSearch: () -> Unit,
) {
    when {
        state.isLoading -> if (state.layout == LibraryLayout.LIST) {
            ListSkeleton(rows = SKELETON_ROWS, modifier = Modifier.padding(ListPadding))
        } else {
            GridSkeleton(rows = SKELETON_ROWS / 2, modifier = Modifier.padding(ListPadding))
        }
        state.loadFailed -> ErrorState(message = AppError.LocalStorage.message(), onRetry = viewModel::onRetryLoad)
        state.isEmptyLibrary -> BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyLibrary(signedIn = state.signedIn, onScan = onScan, onImport = onImport, modifier = Modifier.fillMaxWidth())
            }
        }
        state.documents.isEmpty() -> NoResults(onClearSearch = onClearSearch)
        else -> DocumentCollection(
            state = state,
            viewModel = viewModel,
            listState = listState,
            gridState = gridState,
            onOpenDocument = onOpenDocument,
        )
    }
}

/** The documents, as rows or as a grid. In a selection, a tap picks instead of opening. */
@Composable
private fun DocumentCollection(
    state: FilesUiState,
    viewModel: FilesViewModel,
    listState: LazyListState,
    gridState: LazyGridState,
    onOpenDocument: (String) -> Unit,
) {
    val entrance = rememberEntranceWindow()
    val selectionOf = { id: String ->
        when {
            !state.selectionMode -> ItemSelection.NONE
            id in state.selectedIds -> ItemSelection.SELECTED
            else -> ItemSelection.UNSELECTED
        }
    }
    val onClick = { id: String -> if (state.selectionMode) viewModel.onToggleSelection(id) else onOpenDocument(id) }
    if (state.layout == LibraryLayout.LIST) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(ItemSpacing),
        ) {
            itemsIndexed(state.documents, key = { _, document -> document.id }, contentType = { _, _ -> DOCUMENT_TYPE }) { index, document ->
                DocumentListItem(
                    document = document,
                    signedIn = state.signedIn,
                    loadCover = viewModel::cover,
                    onClick = { onClick(document.id) },
                    onLongClick = { viewModel.onToggleSelection(document.id) },
                    selection = selectionOf(document.id),
                    modifier = Modifier
                        .animateItem(fadeInSpec = Motion.enter(), placementSpec = Motion.enter(), fadeOutSpec = Motion.exit())
                        .staggeredEntrance(index, entrance),
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = GridCellMinWidth),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = ListPadding,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            itemsIndexed(state.documents, key = { _, document -> document.id }, contentType = { _, _ -> DOCUMENT_TYPE }) { index, document ->
                DocumentGridItem(
                    document = document,
                    signedIn = state.signedIn,
                    loadCover = viewModel::cover,
                    onClick = { onClick(document.id) },
                    onLongClick = { viewModel.onToggleSelection(document.id) },
                    selection = selectionOf(document.id),
                    modifier = Modifier
                        .animateItem(fadeInSpec = Motion.enter(), placementSpec = Motion.enter(), fadeOutSpec = Motion.exit())
                        .staggeredEntrance(index, entrance),
                )
            }
        }
    }
}

@Composable
private fun FilesTitleBar(
    showDocumentActions: Boolean,
    layout: LibraryLayout,
    onOpenSearch: () -> Unit,
    onToggleLayout: () -> Unit,
) {
    ScanovaTopBar(
        title = stringResource(R.string.feature_documents_files_title),
        actions = {
            // Search and layout only mean something once there is a document.
            if (showDocumentActions) {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.feature_documents_search_label))
                }
                IconButton(onClick = onToggleLayout) {
                    if (layout == LibraryLayout.LIST) {
                        Icon(Icons.Rounded.GridView, contentDescription = stringResource(R.string.feature_documents_show_grid))
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.ViewList,
                            contentDescription = stringResource(R.string.feature_documents_show_list),
                        )
                    }
                }
            }
        },
    )
}

/** The contextual bar of an open selection: how many are picked, and what can be done with them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    enabled: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = pluralStringResource(R.plurals.feature_documents_selected_count, count, count),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.feature_documents_selection_close))
            }
        },
        actions = {
            IconButton(onClick = onToggleSelectAll, enabled = enabled) {
                if (allSelected) {
                    Icon(Icons.Rounded.Deselect, contentDescription = stringResource(R.string.feature_documents_deselect_all))
                } else {
                    Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.feature_documents_select_all))
                }
            }
            IconButton(onClick = onShare, enabled = enabled) {
                Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.feature_documents_share))
            }
            IconButton(onClick = onSave, enabled = enabled) {
                Icon(Icons.Rounded.SaveAlt, contentDescription = stringResource(R.string.feature_documents_save_to_device))
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.feature_documents_delete))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

/** "12 documents" and the sort order, above the list. */
@Composable
private fun ListHeader(count: Int, sort: DocumentSort, enabled: Boolean, onSortChange: (DocumentSort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg + Spacing.xs, end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pluralStringResource(R.plurals.feature_documents_files_count, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SortMenu(sort = sort, enabled = enabled, onSortChange = onSortChange)
    }
}

@Composable
private fun SortMenu(sort: DocumentSort, enabled: Boolean, onSortChange: (DocumentSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = stringResource(sort.labelRes())
    val description = stringResource(R.string.feature_documents_sort_description, current)
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = MinTouchTarget)
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(SortIconSize))
            Spacer(Modifier.size(Spacing.sm))
            Text(current)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DocumentSort.entries.forEach { option ->
                val chosen = option == sort
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    onClick = {
                        expanded = false
                        onSortChange(option)
                    },
                    trailingIcon = if (chosen) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics { selected = chosen },
                )
            }
        }
    }
}

private fun DocumentSort.labelRes(): Int = when (this) {
    DocumentSort.NEWEST -> R.string.feature_documents_sort_newest
    DocumentSort.OLDEST -> R.string.feature_documents_sort_oldest
    DocumentSort.NAME -> R.string.feature_documents_sort_name
    DocumentSort.SIZE -> R.string.feature_documents_sort_size
}

@Composable
private fun DeleteDialog(count: Int, reachesBackup: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        title = { Text(pluralStringResource(R.plurals.feature_documents_delete_many_title, count, count)) },
        text = {
            Text(pluralStringResource(deletionBodyRes(reachesBackup), count))
        },
        confirmButton = {
            QuietButton(
                text = stringResource(R.string.feature_documents_delete_confirm),
                onClick = onConfirm,
                destructive = true,
            )
        },
        dismissButton = {
            QuietButton(text = stringResource(DesignR.string.core_designsystem_action_cancel), onClick = onDismiss)
        },
    )
}

private const val DOCUMENT_TYPE = "document"
private const val SKELETON_ROWS = 6
private val SortIconSize = 18.dp
