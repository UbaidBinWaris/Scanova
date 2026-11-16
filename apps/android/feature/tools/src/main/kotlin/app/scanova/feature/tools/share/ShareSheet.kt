package app.scanova.feature.tools.share

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.common.result.AppError
import app.scanova.core.data.export.ExportFormat
import app.scanova.core.data.export.ImageQuality
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.component.message
import app.scanova.core.designsystem.theme.Accent
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.ScanovaThemeInfo
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.tools.R
import app.scanova.feature.tools.common.ShareIntents
import app.scanova.feature.tools.common.SheetFrame
import app.scanova.feature.tools.common.formatSize
import app.scanova.feature.tools.common.rememberSheetVisibility
import kotlinx.coroutines.launch

/**
 * The share sheet for one or more documents, or with [SheetPurpose.SAVE] the
 * "Save to device" sheet: the same formats (PDF, JPEG per page, long JPEG,
 * text) and quality, then the system's save dialog. [initialFormat]
 * preselects a format (e.g. "Share as images" from the Tools tab); [onClose]
 * pops the sheet's destination once it has slid away.
 */
@Composable
internal fun ShareSheetRoute(
    documentIds: List<String>,
    initialFormat: ExportFormat?,
    onClose: () -> Unit,
    purpose: SheetPurpose = SheetPurpose.SHARE,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start(documentIds, initialFormat) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val visibility = rememberSheetVisibility()
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            viewModel.onClose()
            scope.launch {
                visibility.hide()
                onClose()
            }
        }
    }
    BackHandler(onBack = close)
    LaunchedEffect(state.closed) { if (state.closed) close() }

    // The share sheet reports back when it closes; that is when the temporary copies are deleted.
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onShareFinished()
    }
    val chooserTitle = stringResource(R.string.feature_tools_share_chooser_title)
    LaunchedEffect(state.readyToShare) {
        val bundle = state.readyToShare ?: return@LaunchedEffect
        viewModel.onShareLaunched()
        val intent = ShareIntents.forFiles(context, bundle.files, bundle.mimeType, chooserTitle)
        if (intent == null) {
            viewModel.onShareFailed()
        } else {
            try {
                shareLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                viewModel.onShareFailed()
            }
        }
    }

    // One "save as" dialog per kind of file, each suggesting the export's own file name.
    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SaveFileKind.PDF.mimeType),
        viewModel::onSaveToFile,
    )
    val saveJpeg = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SaveFileKind.JPEG.mimeType),
        viewModel::onSaveToFile,
    )
    val saveText = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SaveFileKind.TEXT.mimeType),
        viewModel::onSaveToFile,
    )
    val saveFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), viewModel::onSaveToFolder)
    LaunchedEffect(state.readyToSave) {
        val target = state.readyToSave ?: return@LaunchedEffect
        viewModel.onSaveLaunched()
        try {
            when (target) {
                is SaveTarget.OneFile -> when (target.kind) {
                    SaveFileKind.PDF -> savePdf
                    SaveFileKind.JPEG -> saveJpeg
                    SaveFileKind.TEXT -> saveText
                }.launch(target.fileName)
                is SaveTarget.Folder -> saveFolder.launch(null)
            }
        } catch (e: ActivityNotFoundException) {
            viewModel.onSaveCancelled()
        }
    }

    SheetFrame(visibility = visibility, onDismissRequest = close) {
        ShareSheetContent(
            state = state,
            purpose = purpose,
            onFormat = viewModel::onFormat,
            onQuality = viewModel::onQuality,
            onShare = viewModel::onShare,
            onSave = viewModel::onSave,
            onStop = viewModel::onStop,
            onRetryLoad = viewModel::onRetryLoad,
            onClose = close,
        )
    }
}

@Composable
private fun ShareSheetContent(
    state: ShareUiState,
    purpose: SheetPurpose,
    onFormat: (ExportFormat) -> Unit,
    onQuality: (ImageQuality) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onStop: () -> Unit,
    onRetryLoad: () -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(
                    when (purpose) {
                        SheetPurpose.SHARE -> R.string.feature_tools_share_title
                        SheetPurpose.SAVE -> R.string.feature_tools_save_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (state.sources.isNotEmpty()) {
                Text(
                    text = subtitle(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.feature_tools_action_close))
        }
    }
    Spacer(Modifier.height(Spacing.lg))

    when (val content = state.content) {
        ShareContent.Loading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LoadingHeight),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is ShareContent.Failed -> {
            MessageBanner(text = content.error.message())
            Spacer(Modifier.height(Spacing.lg))
            SecondaryButton(
                text = stringResource(R.string.feature_tools_action_retry),
                onClick = onRetryLoad,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ShareContent.Ready -> ReadyContent(state, purpose, onFormat, onQuality, onShare, onSave, onStop)
    }
}

@Composable
private fun ReadyContent(
    state: ShareUiState,
    purpose: SheetPurpose,
    onFormat: (ExportFormat) -> Unit,
    onQuality: (ImageQuality) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onStop: () -> Unit,
) {
    val working = state.working != null
    Label(stringResource(R.string.feature_tools_share_format))
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ExportFormat.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                row.forEach { format ->
                    FormatOption(
                        format = format,
                        selected = state.format == format,
                        available = state.isAvailable(format),
                        enabled = !working,
                        onClick = { onFormat(format) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(Spacing.xl))
    val usesImages = state.format == ExportFormat.IMAGES || state.format == ExportFormat.LONG_IMAGE
    Label(stringResource(R.string.feature_tools_share_quality))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ImageQuality.entries.forEachIndexed { index, quality ->
            SegmentedButton(
                selected = state.quality == quality,
                onClick = { onQuality(quality) },
                enabled = usesImages && !working,
                shape = SegmentedButtonDefaults.itemShape(index, ImageQuality.entries.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(quality.label()))
            }
        }
    }
    Text(
        text = stringResource(
            if (usesImages) R.string.feature_tools_share_quality_hint else R.string.feature_tools_share_quality_not_used,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs),
    )

    Spacer(Modifier.height(Spacing.xl))
    state.summary?.let { summary ->
        Text(
            text = stringResource(
                R.string.feature_tools_share_summary,
                pluralStringResource(R.plurals.feature_tools_file_count, summary.fileCount, summary.fileCount),
                formatSize(summary.estimatedBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.md))
    }
    MessageBanner(
        text = stringResource(
            when (purpose) {
                SheetPurpose.SHARE -> R.string.feature_tools_share_unencrypted_notice
                SheetPurpose.SAVE -> R.string.feature_tools_save_unencrypted_notice
            },
        ),
        tone = BannerTone.Info,
    )

    state.error?.let { error ->
        Spacer(Modifier.height(Spacing.md))
        MessageBanner(text = shareErrorMessage(error))
    }
    state.savedCount?.let { count ->
        Spacer(Modifier.height(Spacing.md))
        MessageBanner(
            text = pluralStringResource(R.plurals.feature_tools_share_saved, count, count),
            tone = BannerTone.Info,
        )
    }

    Spacer(Modifier.height(Spacing.lg))
    WorkingLine(state)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        when {
            working -> QuietButton(
                text = stringResource(R.string.feature_tools_action_stop),
                onClick = onStop,
                modifier = Modifier.weight(1f),
            )
            // Saving is this sheet's one action; sharing has a sheet of its own.
            purpose == SheetPurpose.SAVE -> Unit
            else -> SecondaryButton(
                text = stringResource(R.string.feature_tools_share_save),
                onClick = onSave,
                icon = Icons.Rounded.Download,
                modifier = Modifier.weight(1f),
            )
        }
        when (purpose) {
            SheetPurpose.SHARE -> PrimaryButton(
                text = stringResource(R.string.feature_tools_share_action),
                onClick = onShare,
                icon = Icons.Rounded.Share,
                loading = working,
                enabled = state.isAvailable(state.format),
                modifier = Modifier.weight(1f),
            )
            SheetPurpose.SAVE -> PrimaryButton(
                text = stringResource(saveActionLabel(state.format)),
                onClick = onSave,
                icon = Icons.Rounded.Download,
                loading = working,
                enabled = state.isAvailable(state.format),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Save PDF", "Save JPEGs", …: the action says what will be saved. */
private fun saveActionLabel(format: ExportFormat): Int = when (format) {
    ExportFormat.PDF -> R.string.feature_tools_save_action_pdf
    ExportFormat.IMAGES -> R.string.feature_tools_save_action_jpeg
    ExportFormat.LONG_IMAGE -> R.string.feature_tools_save_action_long_jpeg
    ExportFormat.TEXT -> R.string.feature_tools_save_action_text
}

/** Progress of the export, in a fixed-height line so the buttons never jump. */
@Composable
private fun WorkingLine(state: ShareUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkingLineHeight)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (state.working != null) {
            val progress = state.progress
            if (progress.total > 0) {
                val fraction by animateFloatAsState(progress.fraction, Motion.quick(), label = "shareProgress")
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = if (progress.total > 0) {
                    stringResource(R.string.feature_tools_share_preparing_count, progress.done, progress.total)
                } else {
                    stringResource(R.string.feature_tools_share_preparing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FormatOption(
    format: ExportFormat,
    selected: Boolean,
    available: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val looks = format.looks()
    val dark = ScanovaThemeInfo.isDark
    val shape = MaterialTheme.shapes.large
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = Motion.quick(),
        label = "formatBorder",
    )
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .clip(shape)
            .selectable(
                selected = selected,
                enabled = available && enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .alpha(if (available) 1f else DISABLED_ALPHA),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = looks.accent.container(dark),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = looks.icon,
                            contentDescription = null,
                            tint = looks.accent.content(dark),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = stringResource(looks.label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(if (available) looks.description else looks.unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = Spacing.xs, bottom = Spacing.sm)
            .semantics { heading() },
    )
}

@Composable
private fun subtitle(state: ShareUiState): String {
    val pages = pluralStringResource(R.plurals.feature_tools_page_count, state.pageCount, state.pageCount)
    val single = state.sources.singleOrNull()
    return if (single != null) {
        stringResource(R.string.feature_tools_share_subtitle_single, single.title, pages)
    } else {
        stringResource(
            R.string.feature_tools_share_subtitle_many,
            pluralStringResource(R.plurals.feature_tools_document_count, state.sources.size, state.sources.size),
            pages,
        )
    }
}

@Composable
private fun shareErrorMessage(error: AppError): String = when (error) {
    AppError.Unexpected("share_unavailable") -> stringResource(R.string.feature_tools_share_error_no_app)
    AppError.Unexpected("save_failed") -> stringResource(R.string.feature_tools_share_error_save)
    AppError.Unexpected("pdf_render_unsupported") -> stringResource(R.string.feature_tools_error_needs_android_11)
    AppError.Unexpected("out_of_memory") -> stringResource(R.string.feature_tools_error_too_large)
    else -> error.message()
}

private class FormatLooks(
    val icon: ImageVector,
    val accent: Accent,
    val label: Int,
    val description: Int,
    val unavailable: Int,
)

private fun ExportFormat.looks(): FormatLooks = when (this) {
    ExportFormat.PDF -> FormatLooks(
        Icons.Rounded.PictureAsPdf,
        Accent.EMERALD,
        R.string.feature_tools_format_pdf,
        R.string.feature_tools_format_pdf_body,
        R.string.feature_tools_format_pdf_body,
    )
    ExportFormat.IMAGES -> FormatLooks(
        Icons.Rounded.Collections,
        Accent.TEAL,
        R.string.feature_tools_format_images,
        R.string.feature_tools_format_images_body,
        R.string.feature_tools_format_needs_android_11,
    )
    ExportFormat.LONG_IMAGE -> FormatLooks(
        Icons.Rounded.ViewDay,
        Accent.SLATE,
        R.string.feature_tools_format_long_image,
        R.string.feature_tools_format_long_image_body,
        R.string.feature_tools_format_needs_android_11,
    )
    ExportFormat.TEXT -> FormatLooks(
        Icons.AutoMirrored.Rounded.TextSnippet,
        Accent.AMBER,
        R.string.feature_tools_format_text,
        R.string.feature_tools_format_text_body,
        R.string.feature_tools_format_text_none,
    )
}

private fun ImageQuality.label(): Int = when (this) {
    ImageQuality.HIGH -> R.string.feature_tools_quality_high
    ImageQuality.MEDIUM -> R.string.feature_tools_quality_medium
}

private const val DISABLED_ALPHA = 0.5f
private val LoadingHeight = 240.dp
private val WorkingLineHeight = 40.dp
