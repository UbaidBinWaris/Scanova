package app.scanova.feature.tools.text

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.common.result.AppError
import app.scanova.core.data.export.TextSection
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.LoadingState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SectionHeader
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.BottomActions
import app.scanova.feature.tools.common.ShareIntents
import kotlinx.coroutines.launch

@Composable
internal fun ExtractTextRoute(onBack: () -> Unit, viewModel: ExtractTextViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ready = state.content as? TextContent.Ready
    val hasText = ready != null && ready.sections.isNotEmpty()

    // The share sheet reports back when it closes; a temporary .txt copy is deleted then.
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onShareFinished()
    }
    val chooserTitle = stringResource(R.string.feature_tools_share_chooser_title)
    LaunchedEffect(state.shareText, state.shareFile) {
        val text = state.shareText
        val file = state.shareFile
        val intent = when {
            text != null -> ShareIntents.forText(text, ready?.title.orEmpty(), chooserTitle)
            file != null -> ShareIntents.forFiles(context, file.files, file.mimeType, chooserTitle)
            else -> return@LaunchedEffect
        }
        viewModel.onShareLaunched()
        if (intent == null) {
            viewModel.onShareFailed()
            return@LaunchedEffect
        }
        try {
            shareLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            viewModel.onShareFailed()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), viewModel::onSaveTo)
    val savedText = stringResource(R.string.feature_tools_text_saved)
    LaunchedEffect(state.saved) {
        if (state.saved) {
            snackbarHostState.showSnackbar(savedText)
            viewModel.onMessageShown()
        }
    }

    val copiedText = stringResource(R.string.feature_tools_text_copied)
    val copyFailedText = stringResource(R.string.feature_tools_text_copy_failed)
    val clipLabel = stringResource(R.string.feature_tools_text_clip_label)
    val onCopy: () -> Unit = {
        val text = ready?.plainText.orEmpty()
        val copied = copyToClipboard(context, clipLabel, text)
        // Android 13 and later confirm a copy themselves; earlier versions get a message here.
        if (!copied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scope.launch { snackbarHostState.showSnackbar(if (copied) copiedText else copyFailedText) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScanovaTopBar(
                title = stringResource(R.string.feature_tools_text_title),
                onBack = onBack,
                actions = {
                    if (hasText) {
                        IconButton(onClick = viewModel::onShare, enabled = !state.working) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.feature_tools_text_share))
                        }
                        IconButton(
                            onClick = {
                                try {
                                    saveLauncher.launch(viewModel.fileName())
                                } catch (e: ActivityNotFoundException) {
                                    scope.launch { snackbarHostState.showSnackbar(copyFailedText) }
                                }
                            },
                            enabled = !state.working,
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.feature_tools_text_save))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (hasText) {
                BottomActions {
                    PrimaryButton(
                        text = stringResource(R.string.feature_tools_text_copy),
                        onClick = onCopy,
                        icon = Icons.Outlined.ContentCopy,
                        modifier = Modifier.weight(1f),
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
            if (state.working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.actionError?.let { error ->
                MessageBanner(
                    text = actionErrorMessage(error),
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
            when (val content = state.content) {
                TextContent.Loading -> LoadingState(message = stringResource(R.string.feature_tools_text_loading))
                is TextContent.Failed -> ErrorState(message = content.error.message(), onRetry = viewModel::onRetry)
                is TextContent.Ready -> if (content.sections.isEmpty()) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Outlined.Notes,
                        title = stringResource(R.string.feature_tools_text_empty_title),
                        body = stringResource(R.string.feature_tools_text_empty_body),
                        actionLabel = stringResource(R.string.feature_tools_action_back),
                        onAction = onBack,
                    )
                } else {
                    TextSections(content.title, content.sections)
                }
            }
        }
    }
}

@Composable
private fun TextSections(title: String, sections: List<TextSection>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        item(key = "title") {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        itemsIndexed(sections, key = { index, _ -> index }) { _, section ->
            Column {
                section.pageNumber?.let { page ->
                    SectionHeader(stringResource(R.string.feature_tools_text_page_heading, page))
                }
                ScanovaCard(modifier = Modifier.fillMaxWidth()) {
                    // Long-press to select part of the text; "Copy text" below copies all of it.
                    SelectionContainer {
                        Text(
                            text = section.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun actionErrorMessage(error: AppError): String = when (error) {
    AppError.Unexpected("share_unavailable") -> stringResource(R.string.feature_tools_share_error_no_app)
    AppError.Unexpected("save_failed") -> stringResource(R.string.feature_tools_text_save_failed)
    else -> error.message()
}

/**
 * Puts [text] on the clipboard, marked sensitive so Android does not show it in
 * the copy preview or keyboard suggestions. Returns false when it could not be
 * copied (e.g. far too long for the clipboard).
 */
private fun copyToClipboard(context: Context, label: String, text: String): Boolean {
    if (text.isEmpty()) return false
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    }
    return try {
        clipboard.setPrimaryClip(clip)
        true
    } catch (e: RuntimeException) {
        false
    }
}

/** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33), honoured by many older keyboards and clipboard tools too. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
