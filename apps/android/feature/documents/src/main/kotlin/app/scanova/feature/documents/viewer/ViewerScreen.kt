package app.scanova.feature.documents.viewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.MinTouchTarget
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.documents.R
import app.scanova.feature.documents.common.SyncBadge
import app.scanova.feature.documents.common.SyncBadgeKind
import app.scanova.feature.documents.common.formatDate
import app.scanova.feature.documents.common.syncBadgeKind
import java.io.File
import kotlin.math.abs

@Composable
internal fun ViewerRoute(onBack: () -> Unit, viewModel: ViewerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.closed) {
        if (state.closed) onBack()
    }

    // The share sheet reports back when it closes; that is when the temporary copy is deleted.
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onShareFinished()
    }
    LaunchedEffect(state.shareFile) {
        val file = state.shareFile ?: return@LaunchedEffect
        viewModel.onShareStarted()
        val intent = shareIntent(context, file)
        if (intent == null) viewModel.onShareFinished() else shareLauncher.launch(intent)
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
            snackbarHostState.showSnackbar(copySavedText)
            viewModel.onMessageShown()
        }
    }

    val document = state.document
    val onSaveCopy = { document?.let { saveCopyLauncher.launch(exportDisplayName(it.title)) } }
    val onShare = { viewModel.onShowDialog(ViewerDialog.SHARE_WARNING) }
    Scaffold(
        topBar = {
            ScanovaTopBar(
                title = document?.title.orEmpty(),
                onBack = onBack,
                actions = {
                    if (document != null) {
                        IconButton(onClick = onShare, enabled = !state.isWorking) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.feature_documents_share))
                        }
                        ViewerMenu(
                            canRetry = document.syncState == SyncState.FAILED,
                            enabled = !state.isWorking,
                            onDialog = viewModel::onShowDialog,
                            onSaveCopy = { onSaveCopy() },
                            onRetryUpload = viewModel::onRetryUpload,
                        )
                    }
                },
            )
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
                    onEditTags = { viewModel.onShowDialog(ViewerDialog.TAGS) },
                    onRetryUpload = viewModel::onRetryUpload,
                )
            }
            when (val content = state.content) {
                ViewerContent.Loading -> LoadingState(message = stringResource(R.string.feature_documents_viewer_loading))
                is ViewerContent.Failed -> ErrorState(message = content.error.message(), onRetry = viewModel::onRetryLoad)
                ViewerContent.PreviewUnsupported -> PreviewUnsupported(
                    enabled = !state.isWorking,
                    onShare = onShare,
                    onSaveCopy = { onSaveCopy() },
                )
                is ViewerContent.Pages -> PageList(content.aspectRatios, viewModel::pageBitmap)
            }
        }
    }

    if (document != null) {
        ViewerDialogs(state.dialog, document, state.signedIn, viewModel)
    }
}

/** A thin bar while a share, export or edit runs. Its space is kept, so nothing jumps. */
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
    onEditTags: () -> Unit,
    onRetryUpload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            document.tags.forEach { tag -> TagPill(tag) }
            AssistChip(
                onClick = onEditTags,
                enabled = !working,
                label = {
                    Text(
                        stringResource(
                            if (document.tags.isEmpty()) R.string.feature_documents_add_tags else R.string.feature_documents_edit_tags,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Sell, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.outlineVariant),
            )
        }
        if (document.syncState == SyncState.FAILED) {
            BackupFailedNotice(error = document.syncError, enabled = !working, onRetry = onRetryUpload)
        }
        actionError?.let { MessageBanner(text = it.message(), modifier = Modifier.padding(top = Spacing.xs)) }
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

/** A tag, read-only. Centred in a touch-target-tall box so it lines up with the chip beside it. */
@Composable
private fun TagPill(tag: String) {
    Box(modifier = Modifier.heightIn(min = MinTouchTarget), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = Spacing.xxl)
                    .padding(horizontal = Spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = tag, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun BackupFailedNotice(error: AppError?, enabled: Boolean, onRetry: () -> Unit) {
    ScanovaCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        contentPadding = PaddingValues(start = Spacing.lg, top = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
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

@Composable
private fun ViewerMenu(
    canRetry: Boolean,
    enabled: Boolean,
    onDialog: (ViewerDialog) -> Unit,
    onSaveCopy: () -> Unit,
    onRetryUpload: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, enabled = enabled) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.feature_documents_more_actions))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        @Composable
        fun item(label: Int, icon: ImageVector, destructive: Boolean = false, action: () -> Unit) = DropdownMenuItem(
            text = { Text(stringResource(label)) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            colors = if (destructive) {
                MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                )
            } else {
                MenuDefaults.itemColors()
            },
            onClick = {
                expanded = false
                action()
            },
        )
        if (canRetry) item(R.string.feature_documents_retry_upload, Icons.Outlined.CloudUpload, action = onRetryUpload)
        item(R.string.feature_documents_rename, Icons.Outlined.Edit) { onDialog(ViewerDialog.RENAME) }
        item(R.string.feature_documents_edit_tags, Icons.Outlined.Sell) { onDialog(ViewerDialog.TAGS) }
        item(R.string.feature_documents_save_copy, Icons.Outlined.SaveAlt, action = onSaveCopy)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        item(R.string.feature_documents_delete, Icons.Outlined.Delete, destructive = true) { onDialog(ViewerDialog.DELETE) }
    }
}

/** Pages as sheets of paper, with a small "Page 2 of 5" pill while there is more than one. */
@Composable
private fun PageList(aspectRatios: List<Float>, loadPage: suspend (Int, Int) -> Bitmap?) {
    val listState = rememberLazyListState()
    val currentPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: 0
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.sm,
                // Room for the page pill under the last page.
                bottom = Spacing.xxl + Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(count = aspectRatios.size, key = { it }) { index ->
                Page(
                    index = index,
                    count = aspectRatios.size,
                    aspectRatio = aspectRatios[index],
                    widthPx = widthPx,
                    loadPage = loadPage,
                )
            }
        }
        if (aspectRatios.size > 1) {
            PageIndicator(
                page = currentPage + 1,
                count = aspectRatios.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Spacing.lg),
            )
        }
    }
}

@Composable
private fun Page(index: Int, count: Int, aspectRatio: Float, widthPx: Int, loadPage: suspend (Int, Int) -> Bitmap?) {
    val description = stringResource(R.string.feature_documents_viewer_page_description, index + 1, count)
    // null = still loading; a failed page is reported as text.
    val page by produceState<Result<Bitmap>?>(initialValue = null, index, widthPx) {
        val bitmap = loadPage(index, widthPx)
        value = if (bitmap != null) Result.success(bitmap) else Result.failure(IllegalStateException())
    }
    val shape = MaterialTheme.shapes.extraSmall
    Crossfade(
        targetState = page,
        animationSpec = tween(PAGE_FADE_MILLIS),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio.coerceIn(0.2f, 5f))
            .clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)), shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        label = "page",
    ) { result ->
        val bitmap = result?.getOrNull()
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                // Still loading: the empty sheet is enough.
                result == null -> Unit
                else -> Text(
                    text = stringResource(R.string.feature_documents_viewer_page_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(page: Int, count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Text(
            text = stringResource(R.string.feature_documents_viewer_page_description, page, count),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
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
        IconTile(icon = Icons.Outlined.Description, size = PreviewTileSize)
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
                icon = Icons.Outlined.Share,
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
                text = {
                    Text(
                        stringResource(
                            if (inBackup) R.string.feature_documents_delete_body_backup else R.string.feature_documents_delete_body_local,
                        ),
                    )
                },
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
        ViewerDialog.SHARE_WARNING -> AlertDialog(
            onDismissRequest = viewModel::onDismissDialog,
            title = { Text(stringResource(R.string.feature_documents_share_warning_title)) },
            text = { Text(stringResource(R.string.feature_documents_share_warning_body)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_documents_share_confirm),
                    onClick = viewModel::onShareConfirmed,
                )
            },
            dismissButton = { QuietButton(text = cancel, onClick = viewModel::onDismissDialog) },
        )
    }
}

/** Builds the share sheet intent for a temporary export. Null when the file cannot be offered. */
private fun shareIntent(context: Context, file: File): Intent? {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    }.getOrNull() ?: return null
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        // Read access to this one URI, for the receiving app only.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, context.getString(R.string.feature_documents_share_chooser_title))
}

private const val PAGE_FADE_MILLIS = 180
private val PreviewTileSize = 56.dp

/** Keeps centred text at a comfortable line length. */
private val ReadableWidth = 360.dp
