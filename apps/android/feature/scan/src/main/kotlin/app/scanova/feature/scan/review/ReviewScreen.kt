package app.scanova.feature.scan.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanova.core.designsystem.component.EmptyState
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaTextField
import app.scanova.core.designsystem.component.ScanovaTopBar
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.scan.R

@Composable
internal fun ReviewRoute(
    onAddPage: () -> Unit,
    onAdjustPage: (pageId: String) -> Unit,
    onSaved: (documentId: String) -> Unit,
    onDiscarded: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedDocumentId) {
        state.savedDocumentId?.let(onSaved)
    }
    LaunchedEffect(state.discarded) {
        if (state.discarded) onDiscarded()
    }
    BackHandler(enabled = !state.isSaving, onBack = viewModel::onDiscardRequested)

    Scaffold(
        topBar = {
            ScanovaTopBar(
                title = stringResource(R.string.feature_scan_review_title),
                onBack = viewModel::onDiscardRequested,
            )
        },
        bottomBar = {
            if (state.pages.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        if (state.saveFailed) {
                            MessageBanner(text = stringResource(R.string.feature_scan_review_save_failed))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            SecondaryButton(
                                text = stringResource(R.string.feature_scan_review_add_page),
                                onClick = onAddPage,
                                enabled = !state.isSaving,
                                icon = Icons.Outlined.AddAPhoto,
                                modifier = Modifier.weight(1f),
                            )
                            PrimaryButton(
                                text = stringResource(
                                    if (state.isSaving) {
                                        R.string.feature_scan_review_saving
                                    } else {
                                        R.string.feature_scan_review_save
                                    },
                                ),
                                onClick = viewModel::onSave,
                                loading = state.isSaving,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.pages.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.DocumentScanner,
                title = stringResource(R.string.feature_scan_review_empty_title),
                body = stringResource(R.string.feature_scan_review_empty_body),
                actionLabel = stringResource(R.string.feature_scan_review_scan_page),
                actionIcon = Icons.Outlined.CameraAlt,
                onAction = onAddPage,
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "title") {
                    ScanovaTextField(
                        value = state.title,
                        onValueChange = viewModel::onTitleChange,
                        label = stringResource(R.string.feature_scan_review_title_label),
                        enabled = !state.isSaving,
                        errorText = if (state.titleMissing) {
                            stringResource(R.string.feature_scan_review_title_required)
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                    PageCard(
                        page = page,
                        number = index + 1,
                        total = state.pages.size,
                        enabled = !state.isSaving,
                        onMoveEarlier = { viewModel.onMovePage(page.id, -1) },
                        onMoveLater = { viewModel.onMovePage(page.id, 1) },
                        onAdjust = { onAdjustPage(page.id) },
                        onRetake = {
                            viewModel.onRetakePage(page.id)
                            onAddPage()
                        },
                        onDelete = { viewModel.onDeletePage(page.id) },
                    )
                }
            }
        }
    }

    if (state.confirmingDiscard) {
        AlertDialog(
            onDismissRequest = viewModel::onDiscardDismissed,
            title = { Text(stringResource(R.string.feature_scan_review_discard_title)) },
            text = { Text(stringResource(R.string.feature_scan_review_discard_body)) },
            confirmButton = {
                QuietButton(
                    text = stringResource(R.string.feature_scan_review_discard_confirm),
                    onClick = viewModel::onDiscardConfirmed,
                    destructive = true,
                )
            },
            dismissButton = {
                QuietButton(
                    text = stringResource(R.string.feature_scan_review_keep),
                    onClick = viewModel::onDiscardDismissed,
                )
            },
        )
    }
}

/**
 * One page in the review list. Reordering uses explicit "earlier" and "later"
 * buttons rather than drag-and-drop: they work the same for touch, keyboards,
 * switches and screen readers, and cannot be triggered by accident while scrolling.
 */
@Composable
private fun PageCard(
    page: ReviewPage,
    number: Int,
    total: Int,
    enabled: Boolean,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onAdjust: () -> Unit,
    onRetake: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnail = remember(page.thumbnail) { page.thumbnail.asImageBitmap() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                bitmap = thumbnail,
                contentDescription = stringResource(R.string.feature_scan_review_page_image_description, number),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(84.dp)
                    .height(112.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.feature_scan_review_page_label, number, total),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row {
                    IconButton(onClick = onMoveEarlier, enabled = enabled && number > 1) {
                        Icon(
                            Icons.Outlined.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.feature_scan_review_move_earlier, number),
                        )
                    }
                    IconButton(onClick = onMoveLater, enabled = enabled && number < total) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.feature_scan_review_move_later, number),
                        )
                    }
                    IconButton(onClick = onAdjust, enabled = enabled) {
                        Icon(
                            Icons.Outlined.Crop,
                            contentDescription = stringResource(R.string.feature_scan_review_adjust, number),
                        )
                    }
                    IconButton(onClick = onRetake, enabled = enabled) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = stringResource(R.string.feature_scan_review_retake, number),
                        )
                    }
                    IconButton(onClick = onDelete, enabled = enabled) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.feature_scan_review_delete, number),
                            tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}
