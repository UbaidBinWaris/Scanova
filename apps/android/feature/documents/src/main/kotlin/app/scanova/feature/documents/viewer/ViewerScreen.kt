package app.scanova.feature.documents.viewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.data.documents.exportDisplayName
import app.scanova.core.data.model.Document
import app.scanova.core.data.model.SyncState
import app.scanova.core.designsystem.R as DesignR
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.documents.R
import app.scanova.feature.documents.common.SyncBadge
import java.io.File

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

    val document = state.document
    Scaffold(
        topBar = {
            ScanovaTopBar(
                title = document?.title.orEmpty(),
                onBack = onBack,
                actions = {
                    if (document != null) {
                        ViewerMenu(
                            canRetry = document.syncState == SyncState.FAILED,
                            enabled = !state.isWorking,
                            onDialog = viewModel::onShowDialog,
                            onSaveCopy = { saveCopyLauncher.launch(exportDisplayName(document.title)) },
                            onRetryUpload = viewModel::onRetryUpload,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (document != null) {
                DocumentHeader(document, state, viewModel::onMessageShown)
            }
            when (val content = state.content) {
                ViewerContent.Loading -> LoadingState(message = stringResource(R.string.feature_documents_viewer_loading))
                is ViewerContent.Failed -> ErrorState(message = content.error.message(), onRetry = viewModel::onRetryLoad)
                ViewerContent.PreviewUnsupported -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.feature_documents_viewer_preview_unsupported),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    SecondaryButton(
                        text = stringResource(R.string.feature_documents_share),
                        onClick = { viewModel.onShowDialog(ViewerDialog.SHARE_WARNING) },
                        icon = Icons.Outlined.Share,
                    )
                }
                is ViewerContent.Pages -> PageList(content.aspectRatios, viewModel::pageBitmap)
            }
        }
    }

    if (document != null) {
        ViewerDialogs(state.dialog, document, viewModel)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentHeader(document: Document, state: ViewerUiState, onMessageShown: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SyncBadge(document)
        document.syncError?.let { MessageBanner(text = it.message()) }
        if (document.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                document.tags.forEach { tag -> SuggestionChip(onClick = {}, enabled = false, label = { Text(tag) }) }
            }
        }
        state.actionError?.let { MessageBanner(text = it.message()) }
        if (state.isWorking) {
            MessageBanner(text = stringResource(R.string.feature_documents_working), tone = BannerTone.Info)
        }
        if (state.copySaved) {
            MessageBanner(text = stringResource(R.string.feature_documents_copy_saved), tone = BannerTone.Info)
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(COPY_SAVED_MESSAGE_MILLIS)
                onMessageShown()
            }
        }
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
        fun item(label: Int, action: () -> Unit) = DropdownMenuItem(
            text = { Text(stringResource(label)) },
            onClick = {
                expanded = false
                action()
            },
        )
        if (canRetry) item(R.string.feature_documents_retry_upload, onRetryUpload)
        item(R.string.feature_documents_rename) { onDialog(ViewerDialog.RENAME) }
        item(R.string.feature_documents_edit_tags) { onDialog(ViewerDialog.TAGS) }
        item(R.string.feature_documents_share) { onDialog(ViewerDialog.SHARE_WARNING) }
        item(R.string.feature_documents_save_copy, onSaveCopy)
        item(R.string.feature_documents_delete) { onDialog(ViewerDialog.DELETE) }
    }
}

@Composable
private fun PageList(aspectRatios: List<Float>, loadPage: suspend (Int, Int) -> Bitmap?) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(count = aspectRatios.size, key = { it }) { index ->
                val description = stringResource(
                    R.string.feature_documents_viewer_page_description,
                    index + 1,
                    aspectRatios.size,
                )
                // null = still loading; a failed page is reported as text.
                val page by produceState<Result<Bitmap>?>(initialValue = null, index, widthPx) {
                    val bitmap = loadPage(index, widthPx)
                    value = if (bitmap != null) Result.success(bitmap) else Result.failure(IllegalStateException())
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatios[index].coerceIn(0.2f, 5f))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = page?.getOrNull()
                    when {
                        bitmap != null -> Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = description,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        page == null -> CircularProgressIndicator()
                        else -> Text(
                            text = stringResource(R.string.feature_documents_viewer_page_failed),
                            color = Color.Black,
                            modifier = Modifier.padding(Spacing.lg),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerDialogs(dialog: ViewerDialog?, document: Document, viewModel: ViewerViewModel) {
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
        ViewerDialog.DELETE -> AlertDialog(
            onDismissRequest = viewModel::onDismissDialog,
            title = { Text(stringResource(R.string.feature_documents_delete_title)) },
            text = { Text(stringResource(R.string.feature_documents_delete_body)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_documents_delete_confirm),
                    onClick = viewModel::onDeleteConfirmed,
                    destructive = true,
                )
            },
            dismissButton = { QuietButton(text = cancel, onClick = viewModel::onDismissDialog) },
        )
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

private const val COPY_SAVED_MESSAGE_MILLIS = 4000L
