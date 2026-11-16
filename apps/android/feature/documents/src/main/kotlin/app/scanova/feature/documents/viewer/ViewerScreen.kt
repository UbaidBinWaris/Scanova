package app.scanova.feature.documents.viewer

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.common.result.AppError
import app.scanova.core.data.documents.exportDisplayName
import app.scanova.core.data.model.Document
import app.scanova.core.data.model.SyncState
import app.scanova.core.designsystem.R as DesignR
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.IconTile
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.component.raisedCardColor
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.documents.R
import app.scanova.feature.documents.common.ReadableWidth
import app.scanova.feature.documents.common.SyncBadge
import app.scanova.feature.documents.common.SyncBadgeKind
import app.scanova.feature.documents.common.deletionBodyRes
import app.scanova.feature.documents.common.formatDate
import app.scanova.feature.documents.common.hairline
import app.scanova.feature.documents.common.syncBadgeKind
import kotlinx.coroutines.launch

/**
 * One document: its pages to swipe through with a strip of thumbnails, and the
 * actions on it at the bottom (edit, share, text, more).
 */
@Composable
internal fun ViewerRoute(
    onBack: () -> Unit,
    onEditDocument: (documentId: String, pageIndex: Int) -> Unit,
    onShareDocument: (documentId: String) -> Unit,
    onExtractText: (documentId: String) -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.closed) {
        if (state.closed) onBack()
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val output = uri?.let { runCatching { context.contentResolver.openOutputStream(it) }.getOrNull() }
        if (output != null) viewModel.onSaveCopyTo(output)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val copySavedText = stringResource(R.string.feature_documents_copy_saved)
    LaunchedEffect(state.copySaved) {
        if (state.copySaved) {
            viewModel.onMessageShown()
            snackbarHostState.showSnackbar(copySavedText)
        }
    }
    val editUnavailableText = stringResource(R.string.feature_documents_edit_unavailable)

    val pages = state.content as? ViewerContent.Pages
    val pagerState = rememberPagerState { pages?.aspectRatios?.size ?: 0 }
    val document = state.document
    val onSaveCopy = { document?.let { saveCopyLauncher.launch(exportDisplayName(it.title)) } }

    Scaffold(
        topBar = { ScanovaTopBar(title = document?.title.orEmpty(), onBack = onBack) },
        bottomBar = {
            if (document != null) {
                ViewerActionBar(
                    enabled = !state.isWorking,
                    canRetryUpload = document.syncState == SyncState.FAILED,
                    onEdit = {
                        if (state.canEdit) {
                            onEditDocument(document.id, pagerState.currentPage)
                        } else {
                            scope.launch { snackbarHostState.showSnackbar(editUnavailableText) }
                        }
                    },
                    onShare = { onShareDocument(document.id) },
                    onExtractText = { onExtractText(document.id) },
                    onRename = { viewModel.onShowDialog(ViewerDialog.RENAME) },
                    onEditTags = { viewModel.onShowDialog(ViewerDialog.TAGS) },
                    onSaveCopy = { onSaveCopy() },
                    onRetryUpload = viewModel::onRetryUpload,
                    onDelete = { viewModel.onShowDialog(ViewerDialog.DELETE) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            WorkingIndicator(state.isWorking)
            if (document != null) {
                DocumentInfo(
                    document = document,
                    signedIn = state.signedIn,
                    actionError = state.actionError,
                    working = state.isWorking,
                    onRetryUpload = viewModel::onRetryUpload,
                )
            }
            // The desk the pages lie on.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (val content = state.content) {
                        ViewerContent.Loading -> LoadingState(message = stringResource(R.string.feature_documents_viewer_loading))
                        is ViewerContent.Failed -> ErrorState(message = content.error.message(), onRetry = viewModel::onRetryLoad)
                        ViewerContent.PreviewUnsupported -> PreviewUnsupported(
                            enabled = !state.isWorking,
                            onShare = { document?.let { onShareDocument(it.id) } },
                            onSaveCopy = { onSaveCopy() },
                        )
                        is ViewerContent.Pages -> PagePager(
                            pages = content,
                            pagerState = pagerState,
                            loadPage = viewModel::pageBitmap,
                        )
                    }
                }
                if (pages != null && pages.aspectRatios.size > 1) {
                    ThumbnailStrip(pages = pages, pagerState = pagerState, loadPage = viewModel::pageBitmap)
                }
            }
        }
    }

    if (document != null) {
        ViewerDialogs(state.dialog, document, state.signedIn, viewModel)
    }
}

/** Loads a page or thumbnail at a pixel width; the screen passes [ViewerViewModel.pageBitmap]. */
internal typealias PageLoader = suspend (index: Int, widthPx: Int) -> Bitmap?

/** A thin bar while an export or edit runs. Its space is kept, so nothing jumps. */
@Composable
private fun WorkingIndicator(working: Boolean) {
    if (working) {
        val description = stringResource(R.string.feature_documents_working)
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.xs)
                .semantics {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
        )
    } else {
        Spacer(Modifier.height(Spacing.xs))
    }
}

/** Date, pages, size and backup state; the tags; and anything that needs the user's attention. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentInfo(
    document: Document,
    signedIn: Boolean,
    actionError: AppError?,
    working: Boolean,
    onRetryUpload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = viewerDetails(document),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // A refused backup gets its own notice below.
            SyncBadge(syncBadgeKind(document, signedIn, showBackedUp = true).takeIf { it != SyncBadgeKind.Failed })
        }
        if (document.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                document.tags.forEach { tag -> TagPill(tag) }
            }
        }
        if (document.syncState == SyncState.FAILED) {
            BackupFailedNotice(error = document.syncError, enabled = !working, onRetry = onRetryUpload)
        }
        actionError?.let { MessageBanner(text = it.message()) }
    }
}

/** "12 Nov 2026 · 3 pages · 1.2 MB" (the size only when known). */
@Composable
private fun viewerDetails(document: Document): String {
    val context = LocalContext.current
    val date = formatDate(document.capturedAt)
    val pages = pluralStringResource(R.plurals.feature_documents_page_count, document.pageCount, document.pageCount)
    val size = document.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    return if (size == null) {
        stringResource(R.string.feature_documents_item_details, date, pages)
    } else {
        stringResource(R.string.feature_documents_viewer_details, date, pages, size)
    }
}

/** A tag, read-only; tags are changed from "More". */
@Composable
private fun TagPill(tag: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

@Composable
private fun BackupFailedNotice(error: AppError?, enabled: Boolean, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = raisedCardColor(),
        border = hairline(),
    ) {
        Column(modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md, end = Spacing.sm, bottom = Spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.padding(end = Spacing.sm)) {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(Spacing.xl),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = stringResource(R.string.feature_documents_backup_failed_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (error != null) {
                        Text(
                            text = error.message(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            QuietButton(
                text = stringResource(R.string.feature_documents_backup_retry),
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** A document from another device on an Android version that cannot preview it in memory. */
@Composable
private fun PreviewUnsupported(enabled: Boolean, onShare: () -> Unit, onSaveCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = Icons.Rounded.Description, size = PreviewTileSize)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.feature_documents_viewer_preview_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = ReadableWidth),
        )
        Spacer(Modifier.height(Spacing.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SecondaryButton(
                text = stringResource(R.string.feature_documents_share),
                onClick = onShare,
                enabled = enabled,
                icon = Icons.Rounded.Share,
            )
            QuietButton(text = stringResource(R.string.feature_documents_save_copy), onClick = onSaveCopy, enabled = enabled)
        }
    }
}

@Composable
private fun ViewerDialogs(dialog: ViewerDialog?, document: Document, signedIn: Boolean, viewModel: ViewerViewModel) {
    val cancel = stringResource(DesignR.string.core_designsystem_action_cancel)
    val save = stringResource(DesignR.string.core_designsystem_action_save)
    when (dialog) {
        null -> Unit
        ViewerDialog.RENAME -> {
            var title by rememberSaveable { mutableStateOf(document.title) }
            AlertDialog(
                onDismissRequest = viewModel::onDismissDialog,
                title = { Text(stringResource(R.string.feature_documents_rename_title)) },
                text = {
                    ScanovaTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = stringResource(R.string.feature_documents_rename_label),
                        errorText = if (title.isBlank()) stringResource(R.string.feature_documents_rename_required) else null,
                    )
                },
                confirmButton = {
                    QuietButton(text = save, onClick = { viewModel.onRename(title) }, enabled = title.isNotBlank())
                },
                dismissButton = { QuietButton(text = cancel, onClick = viewModel::onDismissDialog) },
            )
        }
        ViewerDialog.TAGS -> {
            var tags by rememberSaveable { mutableStateOf(document.tags.joinToString(", ")) }
            AlertDialog(
                onDismissRequest = viewModel::onDismissDialog,
                title = { Text(stringResource(R.string.feature_documents_tags_title)) },
                text = {
                    ScanovaTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = stringResource(R.string.feature_documents_tags_label),
                        supportingText = stringResource(R.string.feature_documents_tags_hint),
                        singleLine = false,
                    )
                },
                confirmButton = { QuietButton(text = save, onClick = { viewModel.onSetTags(tags) }) },
                dismissButton = { QuietButton(text = cancel, onClick = viewModel::onDismissDialog) },
            )
        }
        ViewerDialog.DELETE -> {
            // Only a document that reached the account has a copy there to remove later.
            val inBackup = signedIn && document.syncState != SyncState.LOCAL_ONLY
            AlertDialog(
                onDismissRequest = viewModel::onDismissDialog,
                title = { Text(stringResource(R.string.feature_documents_delete_title)) },
                text = { Text(pluralStringResource(deletionBodyRes(inBackup), 1)) },
                confirmButton = {
                    QuietButton(
                        text = stringResource(R.string.feature_documents_delete_confirm),
                        onClick = viewModel::onDeleteConfirmed,
                        destructive = true,
                    )
                },
                dismissButton = { QuietButton(text = cancel, onClick = viewModel::onDismissDialog) },
            )
        }
    }
}

private val PreviewTileSize = 56.dp
