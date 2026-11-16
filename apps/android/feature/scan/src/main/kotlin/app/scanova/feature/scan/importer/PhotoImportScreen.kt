package app.scanova.feature.scan.importer

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.ErrorState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R

/**
 * Photos from the gallery into the scan. The photo picker opens straight
 * away (the system's documents picker on phones without it); this screen
 * shows the progress while the photos become pages, what could not be used,
 * and then hands over to the review.
 *
 * @param onReview shows the review of the scan's pages.
 * @param onClose leaves the scan flow (nothing was imported and there were no pages before).
 */
@Composable
internal fun PhotoImportRoute(
    onReview: () -> Unit,
    onClose: () -> Unit,
    viewModel: PhotoImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.onPicked(uris)
    }
    LaunchedEffect(state.launchPicker) {
        if (!state.launchPicker) return@LaunchedEffect
        viewModel.onPickerLaunched()
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            viewModel.onPickerUnavailable()
        }
    }
    LaunchedEffect(state.exit) {
        when (state.exit ?: return@LaunchedEffect) {
            PhotoImportExit.REVIEW -> onReview()
            PhotoImportExit.CLOSE -> onClose()
        }
        viewModel.onExitHandled()
    }
    BackHandler(onBack = viewModel::onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScanovaTopBar(title = stringResource(R.string.feature_scan_import_title), onBack = viewModel::onBack) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val stage = state.stage) {
                PhotoImportStage.Picking -> EmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = stringResource(R.string.feature_scan_import_pick_title),
                    body = stringResource(R.string.feature_scan_import_pick_body),
                    actionLabel = stringResource(R.string.feature_scan_import_choose),
                    actionIcon = Icons.Rounded.AddPhotoAlternate,
                    onAction = viewModel::onPickAgain,
                )
                is PhotoImportStage.Working -> WorkingContent(stage, onStop = viewModel::onBack)
                is PhotoImportStage.Partial -> PartialContent(stage.outcome, onContinue = viewModel::onContinue)
                is PhotoImportStage.Failed -> ErrorState(
                    message = stage.outcome.problems.map { problemMessage(it) }.joinToString("\n\n"),
                    retryLabel = stringResource(R.string.feature_scan_import_choose_other),
                    onRetry = viewModel::onPickAgain,
                )
                PhotoImportStage.PickerUnavailable -> ErrorState(message = stringResource(R.string.feature_scan_import_no_picker))
            }
        }
    }
}

/** The latest page, a progress bar that fills smoothly, "Photo 3 of 12" and Stop. */
@Composable
private fun WorkingContent(stage: PhotoImportStage.Working, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LatestPage(stage.preview)
        Spacer(Modifier.height(Spacing.xl))
        Column(
            modifier = Modifier
                .widthIn(max = ReadableWidth)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.feature_scan_import_working),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.lg))
            val fraction by animateFloatAsState(
                targetValue = if (stage.total > 0) stage.done.toFloat() / stage.total else 0f,
                animationSpec = Motion.quick(),
                label = "importProgress",
            )
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(
                    R.string.feature_scan_import_progress,
                    (stage.done + 1).coerceAtMost(stage.total),
                    stage.total,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        QuietButton(text = stringResource(R.string.feature_scan_import_stop), onClick = onStop)
    }
}

/** The page found in the photo imported last; a placeholder until the first one is ready. Decorative. */
@Composable
private fun LatestPage(preview: Bitmap?) {
    val shape = MaterialTheme.shapes.small
    Crossfade(targetState = preview, animationSpec = Motion.quick(), label = "latestPage") { page ->
        if (page == null) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(PreviewHeight * PLACEHOLDER_ASPECT, PreviewHeight),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Spacing.xxl),
                    )
                }
            }
        } else {
            val image = remember(page) { page.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(PreviewHeight)
                    .aspectRatio((page.width.toFloat() / page.height.coerceAtLeast(1)).coerceIn(MIN_ASPECT, MAX_ASPECT))
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            )
        }
    }
}

/** Some photos became pages, some did not: which ones and why, then on to the review. */
@Composable
private fun PartialContent(outcome: PhotoImportOutcome.SomeAdded, onContinue: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Spacing.xxl),
                )
                Text(
                    text = pluralStringResource(R.plurals.feature_scan_import_added, outcome.added, outcome.added),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                outcome.problems.forEach { MessageBanner(text = problemMessage(it), tone = BannerTone.Error) }
                if (outcome.overLimit > 0) {
                    MessageBanner(
                        text = pluralStringResource(
                            R.plurals.feature_scan_import_over_limit,
                            outcome.overLimit,
                            outcome.overLimit,
                            PhotoImportPlan.MAX_PHOTOS,
                        ),
                        tone = BannerTone.Info,
                    )
                }
            }
            Text(
                text = stringResource(R.string.feature_scan_import_partial_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(
                text = stringResource(R.string.feature_scan_import_review),
                onClick = onContinue,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    .fillMaxWidth(),
            )
        }
    }
}

/** "2 photos can't be read: “a.heic”, “b.png”." — the names when known, at most a few. */
@Composable
private fun problemMessage(group: PhotoProblemGroup): String {
    val listed = group.names.take(MAX_NAMES)
    val shown = listed.joinToString(", ") { "“$it”" }
    // Photos without a known name count among the "more" too.
    val more = group.count - listed.size
    val names = when {
        listed.isEmpty() -> null
        more > 0 -> pluralStringResource(R.plurals.feature_scan_import_names_more, more, shown, more)
        else -> shown
    }
    val count = group.count
    return when (group.kind) {
        PhotoProblemKind.UNREADABLE -> if (names != null) {
            pluralStringResource(R.plurals.feature_scan_import_unreadable_named, count, count, names)
        } else {
            pluralStringResource(R.plurals.feature_scan_import_unreadable, count, count)
        }
        PhotoProblemKind.TOO_LARGE -> if (names != null) {
            pluralStringResource(R.plurals.feature_scan_import_too_large_named, count, count, names)
        } else {
            pluralStringResource(R.plurals.feature_scan_import_too_large, count, count)
        }
        PhotoProblemKind.STORAGE -> pluralStringResource(R.plurals.feature_scan_import_storage, count, count)
    }
}

private val PreviewHeight = 160.dp
private val ReadableWidth = 420.dp
private const val PLACEHOLDER_ASPECT = 0.75f
private const val MIN_ASPECT = 0.3f
private const val MAX_ASPECT = 3f
private const val MAX_NAMES = 3
