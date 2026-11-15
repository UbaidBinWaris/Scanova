package app.scanova.feature.documents.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.common.result.AppError
import app.scanova.core.data.model.AppTool
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.component.raisedCardColor
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.documents.R
import app.scanova.feature.documents.common.CoverLoader
import app.scanova.feature.documents.common.DocumentListItem
import app.scanova.feature.documents.common.EmptyLibrary
import app.scanova.feature.documents.common.InlineError
import app.scanova.feature.documents.common.ItemSpacing
import app.scanova.feature.documents.common.ListPadding
import app.scanova.feature.documents.common.ListSkeleton
import app.scanova.feature.documents.common.NoResults
import app.scanova.feature.documents.common.SearchPill
import app.scanova.feature.documents.common.hairline
import app.scanova.feature.documents.common.rememberEntranceWindow
import app.scanova.feature.documents.common.staggeredEntrance

/**
 * Where the app starts: the app's name with the backup chip, search, the quick
 * actions, and the newest documents.
 */
@Composable
internal fun HomeRoute(
    onScan: () -> Unit,
    onTool: (AppTool) -> Unit,
    onShowAllTools: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onSeeAll: () -> Unit,
    onSignIn: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clearSearch = {
        viewModel.onQueryChange("")
        focusManager.clearFocus()
    }
    BackHandler(enabled = state.query.isNotEmpty(), onBack = clearSearch)
    // Kept here, so Home is where it was after a search.
    val dashboardState = rememberLazyListState()

    Scaffold(
        topBar = {
            HomeHeader(
                state = state,
                onBackUp = onSignIn,
                onQueryChange = viewModel::onQueryChange,
                onSearch = { keyboard?.hide() },
            )
        },
    ) { padding ->
        val search = state.search
        AnimatedContent(
            targetState = search is SearchState.Inactive,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "homeContent",
        ) { dashboard ->
            if (dashboard) {
                HomeDashboard(
                    state = state,
                    listState = dashboardState,
                    loadCover = viewModel::cover,
                    onScan = onScan,
                    onTool = onTool,
                    onShowAllTools = onShowAllTools,
                    onOpenDocument = onOpenDocument,
                    onSeeAll = onSeeAll,
                    onRetryLoad = viewModel::onRetryLoad,
                )
            } else {
                SearchResults(
                    search = search,
                    signedIn = state.signedIn,
                    loadCover = viewModel::cover,
                    onOpenDocument = { id ->
                        focusManager.clearFocus()
                        onOpenDocument(id)
                    },
                    onClearSearch = clearSearch,
                )
            }
        }
    }
}

/** The app's name and backup chip on one line, the search field under them. Stays put while the content scrolls. */
@Composable
private fun HomeHeader(
    state: HomeUiState,
    onBackUp: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppTitle(modifier = Modifier.weight(1f))
                // Appears once known, so nobody sees "Back up" flash by while signed in.
                AnimatedVisibility(
                    visible = state.backup != null,
                    enter = fadeIn(Motion.enter()),
                    exit = fadeOut(Motion.exit()),
                ) {
                    state.backup?.let { summary ->
                        BackupChip(
                            summary = summary,
                            onBackUp = onBackUp,
                            modifier = Modifier
                                .padding(start = Spacing.sm)
                                .widthIn(max = ChipMaxWidth),
                        )
                    }
                }
            }
            SearchPill(query = state.query, onQueryChange = onQueryChange, onSearch = onSearch)
        }
    }
}

@Composable
private fun HomeDashboard(
    state: HomeUiState,
    listState: LazyListState,
    loadCover: CoverLoader,
    onScan: () -> Unit,
    onTool: (AppTool) -> Unit,
    onShowAllTools: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onSeeAll: () -> Unit,
    onRetryLoad: () -> Unit,
) {
    val entrance = rememberEntranceWindow()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(ItemSpacing),
    ) {
        item(key = "actions", contentType = "actions") {
            QuickActionsCard(
                onScan = onScan,
                onTool = onTool,
                onShowAllTools = onShowAllTools,
                modifier = Modifier.staggeredEntrance(0, entrance),
            )
        }
        item(key = "recent_title", contentType = "title") {
            SectionTitle(
                text = stringResource(R.string.feature_documents_recent_title),
                actionLabel = stringResource(R.string.feature_documents_see_all).takeIf { state.documentCount > 0 },
                onAction = onSeeAll,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        when {
            state.isLoading -> item(key = "loading", contentType = "loading") { ListSkeleton(rows = SKELETON_ROWS) }
            state.loadFailed -> item(key = "error", contentType = "error") {
                InlineError(message = AppError.LocalStorage.message(), onRetry = onRetryLoad)
            }
            state.isEmptyLibrary -> item(key = "empty", contentType = "empty") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggeredEntrance(1, entrance),
                    shape = MaterialTheme.shapes.large,
                    color = raisedCardColor(),
                    border = hairline(),
                ) {
                    EmptyLibrary(
                        signedIn = state.signedIn,
                        onScan = onScan,
                        onImport = { onTool(AppTool.IMPORT_IMAGES) },
                    )
                }
            }
            else -> itemsIndexed(state.recent, key = { _, document -> document.id }, contentType = { _, _ -> "document" }) { index, document ->
                DocumentListItem(
                    document = document,
                    signedIn = state.signedIn,
                    loadCover = loadCover,
                    onClick = { onOpenDocument(document.id) },
                    modifier = Modifier
                        .animateItem(fadeInSpec = Motion.enter(), placementSpec = Motion.enter(), fadeOutSpec = Motion.exit())
                        .staggeredEntrance(index + 1, entrance),
                )
            }
        }
    }
}

/** What a search found, replacing the dashboard while words are typed. */
@Composable
private fun SearchResults(
    search: SearchState,
    signedIn: Boolean,
    loadCover: CoverLoader,
    onOpenDocument: (String) -> Unit,
    onClearSearch: () -> Unit,
) {
    when (search) {
        // Never shown here: the dashboard takes over.
        SearchState.Inactive -> Unit
        SearchState.Searching -> ListSkeleton(rows = SKELETON_ROWS, modifier = Modifier.padding(ListPadding))
        SearchState.Failed -> ErrorState(message = AppError.LocalStorage.message())
        is SearchState.Results -> if (search.documents.isEmpty()) {
            if (search.upToDate) {
                NoResults(onClearSearch = onClearSearch)
            } else {
                ListSkeleton(rows = SKELETON_ROWS, modifier = Modifier.padding(ListPadding))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = ListPadding,
                verticalArrangement = Arrangement.spacedBy(ItemSpacing),
            ) {
                item(key = "count", contentType = "count") {
                    Text(
                        text = pluralStringResource(
                            R.plurals.feature_documents_search_count,
                            search.documents.size,
                            search.documents.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
                    )
                }
                itemsIndexed(search.documents, key = { _, document -> document.id }, contentType = { _, _ -> "document" }) { _, document ->
                    DocumentListItem(
                        document = document,
                        signedIn = signedIn,
                        loadCover = loadCover,
                        onClick = { onOpenDocument(document.id) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = Motion.enter(),
                            placementSpec = Motion.enter(),
                            fadeOutSpec = Motion.exit(),
                        ),
                    )
                }
            }
        }
    }
}

/** Keeps a long status ("Waiting for a connection…") from squeezing the app's name. */
private val ChipMaxWidth = 200.dp
private const val SKELETON_ROWS = 3

