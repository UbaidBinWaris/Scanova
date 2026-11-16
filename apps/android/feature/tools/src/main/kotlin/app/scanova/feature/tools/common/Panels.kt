package app.scanova.feature.tools.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.scanova.core.designsystem.component.BannerTone
import app.scanova.core.designsystem.component.ListRow
import app.scanova.core.designsystem.component.MessageBanner
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.theme.Motion
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.feature.tools.R

/** Progress of a running tool step: [done] of [total] items (indeterminate while [total] is 0). */
internal data class WorkProgress(val done: Int = 0, val total: Int = 0) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Full-area "working" view for tools that take a while (imports, merges):
 * a title, a progress bar that fills smoothly, a detail line and an optional
 * way to stop. Announced politely to screen readers.
 */
@Composable
internal fun WorkingPanel(
    title: String,
    detail: String?,
    progress: WorkProgress,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ReadableWidth)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.lg))
            if (progress.total > 0) {
                val fraction by animateFloatAsState(progress.fraction, Motion.quick(), label = "workProgress")
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (detail != null) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (onCancel != null) {
            Spacer(Modifier.height(Spacing.xl))
            QuietButton(text = stringResource(R.string.feature_tools_action_stop), onClick = onCancel)
        }
    }
}

/** A document a tool just made. */
internal data class CreatedDocument(val id: String, val title: String, val pageCount: Int)

/**
 * The result of a tool that made several documents (an import of several
 * files, a split): what was created, any files that were skipped, each
 * document openable, and "Done".
 */
@Composable
internal fun CreatedDocumentsPanel(
    documents: List<CreatedDocument>,
    skipped: List<String>,
    onOpen: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item(key = "header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = pluralStringResource(R.plurals.feature_tools_created_title, documents.size, documents.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
            if (skipped.isNotEmpty()) {
                item(key = "skipped") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        skipped.forEach { MessageBanner(text = it, tone = BannerTone.Error) }
                    }
                }
            }
            item(key = "documents") {
                ScanovaCard(contentPadding = PaddingValues(vertical = Spacing.xs)) {
                    documents.forEachIndexed { index, document ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ListRow(
                            title = document.title,
                            supporting = pluralStringResource(R.plurals.feature_tools_page_count, document.pageCount, document.pageCount),
                            leadingIcon = Icons.Rounded.Description,
                            onClick = { onOpen(document.id) },
                        )
                    }
                }
            }
        }
        BottomActions {
            PrimaryButton(
                text = stringResource(R.string.feature_tools_action_done),
                onClick = onDone,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A bar of actions pinned to the bottom of a screen, above the navigation bar. */
@Composable
internal fun BottomActions(
    modifier: Modifier = Modifier,
    above: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            above?.invoke(this)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Keeps centred text at a comfortable line length. */
internal val ReadableWidth = 420.dp
