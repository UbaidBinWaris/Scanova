package app.scanova.feature.tools.importer

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.CreatedDocumentsPanel
import app.scanova.feature.tools.common.WorkingPanel
import app.scanova.feature.tools.common.message

/**
 * Import files: PDFs and pictures from the system file picker, which opens
 * straight away. This screen shows the progress, the result when there is
 * more than one document (or something was skipped, or pictures wait to be
 * scanned), or why nothing could be imported.
 *
 * @param onScanPictures hands picked pictures (content URIs) to the scanner's photo import.
 */
@Composable
internal fun ImportRoute(
    onClose: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onScanPictures: (pictureUris: List<String>) -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onPicked(uris)
    }
    LaunchedEffect(state.launchPicker) {
        if (!state.launchPicker) return@LaunchedEffect
        viewModel.onPickerLaunched()
        try {
            filePicker.launch(arrayOf(DOCUMENT_TYPE_PDF, DOCUMENT_TYPE_IMAGES))
        } catch (e: ActivityNotFoundException) {
            viewModel.onPickerUnavailable()
        }
    }

    LaunchedEffect(state.exit) {
        when (val exit = state.exit ?: return@LaunchedEffect) {
            ImportExit.Close -> onClose()
            is ImportExit.Open -> onOpenDocument(exit.documentId)
            is ImportExit.ScanPictures -> onScanPictures(exit.uris)
        }
        viewModel.onExitHandled()
    }

    val working = state.stage is ImportStage.Working
    // Leaving while importing stops the import; nothing half-made is kept.
    BackHandler(enabled = working, onBack = viewModel::onStop)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScanovaTopBar(
                title = stringResource(R.string.feature_tools_import_files),
                onBack = if (working) viewModel::onStop else onClose,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val stage = state.stage) {
                ImportStage.Picking -> EmptyState(
                    icon = Icons.Rounded.UploadFile,
                    title = stringResource(R.string.feature_tools_import_files_hint_title),
                    body = stringResource(R.string.feature_tools_import_files_hint),
                    actionLabel = stringResource(R.string.feature_tools_import_choose_files),
                    onAction = viewModel::onRetry,
                )
                is ImportStage.Working -> WorkingPanel(
                    title = stringResource(R.string.feature_tools_import_working),
                    detail = workingDetail(stage),
                    progress = stage.progress,
                    onCancel = viewModel::onStop,
                )
                is ImportStage.Failed -> ErrorState(
                    message = stage.error.message(),
                    retryLabel = stringResource(R.string.feature_tools_import_choose_files),
                    onRetry = viewModel::onRetry,
                )
                is ImportStage.Done -> CreatedDocumentsPanel(
                    documents = stage.documents,
                    skipped = stage.skipped.map { it.message() },
                    onOpen = onOpenDocument,
                    onDone = onClose,
                    continueLabel = stage.pictures.takeIf { it.isNotEmpty() }?.let {
                        pluralStringResource(R.plurals.feature_tools_import_scan_pictures, it.size, it.size)
                    },
                    continueHint = stage.pictures.takeIf { it.isNotEmpty() }?.let {
                        pluralStringResource(R.plurals.feature_tools_import_pictures_waiting, it.size, it.size)
                    },
                    onContinue = viewModel::onScanPictures,
                )
            }
        }
    }
}

@Composable
private fun workingDetail(stage: ImportStage.Working): String {
    val progress = stage.progress
    return when {
        stage.fileName == null -> stringResource(R.string.feature_tools_import_preparing)
        progress.total > 0 -> stringResource(R.string.feature_tools_import_page_progress, stage.fileName, progress.done, progress.total)
        else -> stringResource(R.string.feature_tools_import_opening, stage.fileName)
    }
}

private const val DOCUMENT_TYPE_PDF = "application/pdf"
private const val DOCUMENT_TYPE_IMAGES = "image/*"
