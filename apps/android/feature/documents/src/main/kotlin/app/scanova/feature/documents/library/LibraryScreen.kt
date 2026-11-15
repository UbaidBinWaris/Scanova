package app.scanova.feature.documents.library

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.common.result.AppError
import app.scanova.core.data.model.Document
import app.scanova.core.data.settings.LibraryLayout
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.documents.R

@Composable
internal fun LibraryRoute(
    onScan: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searching = searchOpen || state.query.isNotEmpty()
    val closeSearch = {
        searchOpen = false
        viewModel.onQueryChange("")
    }
    BackHandler(enabled = searching, onBack = closeSearch)

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // The scan button shows its label at the top of the library and shrinks to its icon once scrolled.
    val fabExpanded by remember(state.layout) {
        derivedStateOf {
            if (state.layout == LibraryLayout.LIST) listState.firstVisibleItemIndex == 0 else gridState.firstVisibleItemIndex == 0
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val refreshErrorText = state.refreshError?.message()
    LaunchedEffect(refreshErrorText) {
        if (refreshErrorText != null) {
            snackbarHostState.showSnackbar(refreshErrorText)
            viewModel.onRefreshErrorShown()
        }
    }

    Scaffold(
        topBar = {
            if (searching) {
                LibrarySearchBar(query = state.query, onQueryChange = viewModel::onQueryChange, onClose = closeSearch)
            } else {
                LibraryTopBar(
                    showDocumentActions = !state.isLoading && !state.loadFailed && !state.isEmptyLibrary,
                    layout = state.layout,
                    onOpenSearch = { searchOpen = true },
                    onToggleLayout = viewModel::onToggleLayout,
                    onOpenSettings = onOpenSettings,
                )
            }
        },
        floatingActionButton = {
            // The empty library offers the same action in the middle of the screen; one primary action is enough.
            if (!state.isEmptyLibrary) ScanButton(expanded = fabExpanded, onClick = onScan)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val content = @Composable {
            LibraryContent(
                state = state,
                searching = searching,
                listState = listState,
                gridState = gridState,
                loadCover = viewModel::cover,
                onOpenDocument = onOpenDocument,
                onScan = onScan,
                onSignIn = onSignIn,
                onCreateAccount = onCreateAccount,
                onClearSearch = { viewModel.onQueryChange("") },
                onRetryLoad = viewModel::onRetryLoad,
            )
        }
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        // Pulling to refresh fetches changes from the account; without one there is nothing to fetch.
        if (state.signedIn) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::onRefresh,
                modifier = contentModifier,
            ) { content() }
        } else {
            Box(contentModifier) { content() }
        }
    }
}

@Composable
private fun LibraryTopBar(
    showDocumentActions: Boolean,
    layout: LibraryLayout,
    onOpenSearch: () -> Unit,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ScanovaTopBar(
        title = stringResource(R.string.feature_documents_library_title),
        actions = {
            // Search and layout only mean something once there is a document.
            if (showDocumentActions) {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.feature_documents_search_label))
                }
                IconButton(onClick = onToggleLayout) {
                    if (layout == LibraryLayout.LIST) {
                        Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.feature_documents_show_grid))
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.ViewList,
                            contentDescription = stringResource(R.string.feature_documents_show_list),
                        )
                    }
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.feature_documents_open_settings))
            }
        },
    )
}

/** The top bar while searching: the field takes the title's place and opens with the keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.feature_documents_search_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.feature_documents_search_close),
                )
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.feature_documents_search_clear))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** The screen's one primary action. Collapses to its icon while the library is scrolled. */
@Composable
private fun ScanButton(expanded: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.feature_documents_scan)
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        // Material hides the text from accessibility services (expanded or not), so the icon carries the name.
        icon = { Icon(Icons.Outlined.DocumentScanner, contentDescription = label) },
        text = { Text(label) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.loweredElevation(),
    )
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    searching: Boolean,
    listState: LazyListState,
    gridState: LazyGridState,
    loadCover: suspend (String) -> Bitmap?,
    onOpenDocument: (String) -> Unit,
    onScan: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onClearSearch: () -> Unit,
    onRetryLoad: () -> Unit,
) {
    // The backup card or line leads the library, but steps aside while searching.
    val header: (@Composable (Modifier) -> Unit)? = state.backup
        ?.takeIf { !searching }
        ?.let { backup -> { modifier -> BackupHeader(backup, onSignIn, onCreateAccount, modifier) } }

    when {
        state.isLoading -> LibrarySkeleton(state.layout)
        state.loadFailed -> ErrorState(message = AppError.LocalStorage.message(), onRetry = onRetryLoad)
        state.isEmptyLibrary -> EmptyLibraryLayout(
            showBackupCard = state.backup == BackupSummary.Off,
            signedIn = state.signedIn,
            onScan = onScan,
            onSignIn = onSignIn,
            onCreateAccount = onCreateAccount,
        )
        state.documents.isEmpty() -> NoResults(onClearSearch = onClearSearch)
        state.layout == LibraryLayout.LIST -> DocumentList(
            documents = state.documents,
            signedIn = state.signedIn,
            listState = listState,
            header = header,
            loadCover = loadCover,
            onOpenDocument = onOpenDocument,
        )
        else -> DocumentGrid(
            documents = state.documents,
            signedIn = state.signedIn,
            gridState = gridState,
            header = header,
            loadCover = loadCover,
            onOpenDocument = onOpenDocument,
        )
    }
}

@Composable
private fun DocumentList(
    documents: List<Document>,
    signedIn: Boolean,
    listState: LazyListState,
    header: (@Composable (Modifier) -> Unit)?,
    loadCover: suspend (String) -> Bitmap?,
    onOpenDocument: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = LibraryContentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (header != null) {
            item(key = HEADER_KEY, contentType = HEADER_KEY) { header(Modifier.padding(bottom = Spacing.xs)) }
        }
        items(documents, key = { it.id }, contentType = { DOCUMENT_TYPE }) { document ->
            DocumentRow(
                document = document,
                signedIn = signedIn,
                loadCover = loadCover,
                onClick = { onOpenDocument(document.id) },
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(MOTION_MILLIS),
                    placementSpec = tween(MOTION_MILLIS),
                    fadeOutSpec = tween(MOTION_MILLIS),
                ),
            )
        }
    }
}

@Composable
private fun DocumentGrid(
    documents: List<Document>,
    signedIn: Boolean,
    gridState: LazyGridState,
    header: (@Composable (Modifier) -> Unit)?,
    loadCover: suspend (String) -> Bitmap?,
    onOpenDocument: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GridCellMinWidth),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = LibraryContentPadding,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (header != null) {
            item(key = HEADER_KEY, contentType = HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                header(Modifier)
            }
        }
        items(documents, key = { it.id }, contentType = { DOCUMENT_TYPE }) { document ->
            DocumentCell(
                document = document,
                signedIn = signedIn,
                loadCover = loadCover,
                onClick = { onOpenDocument(document.id) },
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(MOTION_MILLIS),
                    placementSpec = tween(MOTION_MILLIS),
                    fadeOutSpec = tween(MOTION_MILLIS),
                ),
            )
        }
    }
}

/**
 * Nothing scanned yet: the invitation to scan sits centred in the space left
 * below the backup card (when there is one), and scrolls on small screens.
 */
@Composable
private fun EmptyLibraryLayout(
    showBackupCard: Boolean,
    signedIn: Boolean,
    onScan: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (showBackupCard) {
                BackupHeader(BackupSummary.Off, onSignIn, onCreateAccount)
            } else {
                Spacer(Modifier)
            }
            EmptyLibrary(signedIn = signedIn, onScan = onScan, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier)
        }
    }
}

private const val HEADER_KEY = "backup_header"
private const val DOCUMENT_TYPE = "document"

/** Short, quiet motion: list changes settle well inside 250 ms. */
internal const val MOTION_MILLIS = 180

private val GridCellMinWidth = 156.dp

/** Height of the floating scan button plus its margin and a gap, so it never covers the last row. */
private val FabClearance = 56.dp + Spacing.lg * 2

private val LibraryContentPadding = PaddingValues(
    start = Spacing.lg,
    end = Spacing.lg,
    top = Spacing.sm,
    bottom = FabClearance,
)
