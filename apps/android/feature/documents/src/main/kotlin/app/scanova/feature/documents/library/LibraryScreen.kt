package app.scanova.feature.documents.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.data.model.Document
import app.scanova.core.data.settings.LibraryLayout
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.core.common.result.AppError
import app.scanova.feature.documents.R
import app.scanova.feature.documents.common.SyncBadge
import app.scanova.feature.documents.common.formatDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryRoute(
    onScan: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isEmptyLibrary = !state.isLoading && !state.loadFailed && state.documents.isEmpty() && state.query.isBlank()

    Scaffold(
        topBar = {
            ScanovaTopBar(
                title = stringResource(R.string.feature_documents_library_title),
                actions = {
                    IconButton(onClick = viewModel::onToggleLayout) {
                        if (state.layout == LibraryLayout.LIST) {
                            Icon(
                                Icons.Outlined.GridView,
                                contentDescription = stringResource(R.string.feature_documents_show_grid),
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Outlined.ViewList,
                                contentDescription = stringResource(R.string.feature_documents_show_list),
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.feature_documents_open_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // The empty library shows the same action in the middle of the screen;
            // one primary action is enough.
            if (!isEmptyLibrary) {
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(Icons.Outlined.DocumentScanner, contentDescription = null) },
                    text = { Text(stringResource(R.string.feature_documents_scan)) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!isEmptyLibrary) {
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
            state.refreshError?.let { error ->
                MessageBanner(
                    text = error.message(),
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
            if (state.pendingSyncCount > 0 && state.refreshError == null) {
                MessageBanner(
                    text = pluralStringResource(
                        R.plurals.feature_documents_pending_sync,
                        state.pendingSyncCount,
                        state.pendingSyncCount,
                    ),
                    tone = BannerTone.Info,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isLoading -> LoadingState()
                    state.loadFailed -> ErrorState(
                        message = AppError.LocalStorage.message(),
                        onRetry = viewModel::onRetryLoad,
                    )
                    isEmptyLibrary -> EmptyState(
                        icon = Icons.Outlined.DocumentScanner,
                        title = stringResource(R.string.feature_documents_empty_title),
                        body = stringResource(R.string.feature_documents_empty_body),
                        actionLabel = stringResource(R.string.feature_documents_scan),
                        actionIcon = Icons.Outlined.DocumentScanner,
                        onAction = onScan,
                    )
                    state.documents.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.feature_documents_no_results_title),
                        body = stringResource(R.string.feature_documents_no_results_body),
                    )
                    state.layout == LibraryLayout.LIST -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Room below the last row so the scan button never covers it.
                        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(state.documents, key = { it.id }) { document ->
                            DocumentRow(document, viewModel::cover) { onOpenDocument(document.id) }
                        }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 156.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        items(state.documents, key = { it.id }) { document ->
                            DocumentTile(document, viewModel::cover) { onOpenDocument(document.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        label = { Text(stringResource(R.string.feature_documents_search_label)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.feature_documents_search_clear))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
private fun documentDetails(document: Document): String = stringResource(
    R.string.feature_documents_item_details,
    formatDate(document.capturedAt),
    pluralStringResource(R.plurals.feature_documents_page_count, document.pageCount, document.pageCount),
)

@Composable
private fun DocumentRow(document: Document, loadCover: suspend (String) -> Bitmap?, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(document, loadCover, Modifier.size(width = 48.dp, height = 64.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = documentDetails(document),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SyncBadge(document)
            }
        }
    }
}

@Composable
private fun DocumentTile(document: Document, loadCover: suspend (String) -> Bitmap?, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Cover(
                document,
                loadCover,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            )
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleSmall,
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
            SyncBadge(document)
        }
    }
}

/** The document's first page when it was scanned on this phone, otherwise a document symbol. */
@Composable
private fun Cover(document: Document, loadCover: suspend (String) -> Bitmap?, modifier: Modifier = Modifier) {
    val cover by produceState<Bitmap?>(initialValue = null, document.id) { value = loadCover(document.id) }
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.feature_documents_cover_description, document.title),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
