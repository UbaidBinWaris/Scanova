package app.scanova.feature.documents.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scanova.core.data.model.AppTool
import app.scanova.core.designsystem.component.QuickActionTile
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.raisedCardColor
import app.scanova.core.designsystem.theme.Accent
import app.scanova.core.designsystem.theme.IconSizes
import app.scanova.core.designsystem.theme.ScanovaThemeInfo
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.core.designsystem.theme.StatusColors
import app.scanova.feature.documents.R
import app.scanova.feature.documents.common.BackupSummary
import app.scanova.feature.documents.common.ScanovaGlyph
import app.scanova.feature.documents.common.hairline
import kotlinx.coroutines.delay

/** The app's mark and name, the one heading of Home. */
@Composable
internal fun AppTitle(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Surface(
            modifier = Modifier.size(MarkSize),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = ScanovaGlyph,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(MarkSize * 0.62f),
                )
            }
        }
        Text(
            text = stringResource(R.string.feature_documents_app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

// ── Backup chip ─────────────────────────────────────────────────────────

/**
 * Backup at a glance. Signed out it is the invitation to back up (opens sign
 * in); signed in it is a quiet status: what is waiting, or when the last
 * backup finished. The words carry the meaning; colour only reinforces it.
 */
@Composable
internal fun BackupChip(summary: BackupSummary, onBackUp: () -> Unit, modifier: Modifier = Modifier) {
    if (summary == BackupSummary.Off) {
        AssistChip(
            onClick = onBackUp,
            label = { Text(stringResource(R.string.feature_documents_chip_back_up)) },
            leadingIcon = {
                Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
            },
            shape = CircleShape,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            border = null,
            modifier = modifier,
        )
        return
    }
    val dark = ScanovaThemeInfo.isDark
    val (icon, tint, text) = when (summary) {
        BackupSummary.Off -> return
        is BackupSummary.UpToDate -> Triple(
            Icons.Rounded.CloudDone,
            if (dark) StatusColors.positiveDark else StatusColors.positiveLight,
            backedUpText(summary),
        )
        is BackupSummary.BackingUp -> Triple(
            Icons.Rounded.CloudSync,
            MaterialTheme.colorScheme.primary,
            pluralStringResource(R.plurals.feature_documents_chip_backing_up, summary.count, summary.count),
        )
        is BackupSummary.WaitingForConnection -> Triple(
            Icons.Rounded.CloudOff,
            if (dark) StatusColors.cautionDark else StatusColors.cautionLight,
            pluralStringResource(R.plurals.feature_documents_chip_offline, summary.count, summary.count),
        )
        is BackupSummary.Failed -> Triple(
            Icons.Rounded.Error,
            MaterialTheme.colorScheme.error,
            pluralStringResource(R.plurals.feature_documents_chip_failed, summary.count, summary.count),
        )
    }
    StatusPill(icon = icon, tint = tint, text = text, modifier = modifier)
}

@Composable
private fun StatusPill(icon: ImageVector, tint: Color, text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = CircleShape,
        color = raisedCardColor(),
        border = hairline(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = PillHeight)
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(IconSizes.small))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Backed up · 5 min. ago", kept current while Home is open. */
@Composable
private fun backedUpText(summary: BackupSummary.UpToDate): String {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(DateUtils.MINUTE_IN_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    val last = summary.lastBackupAt?.toEpochMilli()
    return when {
        last == null -> stringResource(R.string.feature_documents_chip_backup_on)
        now - last < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.feature_documents_chip_backed_up_now)
        else -> stringResource(
            R.string.feature_documents_chip_backed_up_ago,
            DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                .toString(),
        )
    }
}

// ── Quick actions ───────────────────────────────────────────────────────

/** A shortcut on Home: starts [tool], or scans when there is none, or opens the Tools tab ([allTools]). */
private data class QuickAction(
    val label: Int,
    val icon: ImageVector,
    val accent: Accent,
    val tool: AppTool?,
    val allTools: Boolean = false,
)

/**
 * Scan first, then the tools people use most, then the way to every tool.
 * Coloured by kind, as on the Tools tab: capture (emerald), import (teal),
 * text (amber), documents (slate); the icons tell tools of a kind apart.
 */
private val QuickActions = listOf(
    QuickAction(R.string.feature_documents_action_scan, Icons.Rounded.DocumentScanner, Accent.EMERALD, tool = null),
    QuickAction(R.string.feature_documents_action_import_images, Icons.Rounded.PhotoLibrary, Accent.TEAL, AppTool.IMPORT_IMAGES),
    QuickAction(R.string.feature_documents_action_import_files, Icons.Rounded.UploadFile, Accent.TEAL, AppTool.IMPORT_FILES),
    QuickAction(R.string.feature_documents_action_id_card, Icons.Rounded.Badge, Accent.EMERALD, AppTool.ID_CARD),
    QuickAction(
        R.string.feature_documents_action_extract_text,
        Icons.AutoMirrored.Rounded.TextSnippet,
        Accent.AMBER,
        AppTool.EXTRACT_TEXT,
    ),
    QuickAction(R.string.feature_documents_action_merge_pdf, Icons.AutoMirrored.Rounded.CallMerge, Accent.SLATE, AppTool.MERGE_PDF),
    QuickAction(R.string.feature_documents_action_split_pdf, Icons.Rounded.ContentCut, Accent.SLATE, AppTool.SPLIT_PDF),
    QuickAction(R.string.feature_documents_action_all_tools, Icons.Rounded.GridView, Accent.SLATE, tool = null, allTools = true),
)

/** Eight shortcuts, four to a row, on one card. */
@Composable
internal fun QuickActionsCard(
    onScan: () -> Unit,
    onTool: (AppTool) -> Unit,
    onShowAllTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = raisedCardColor(),
        border = hairline(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            QuickActions.chunked(TILES_PER_ROW).forEach { row ->
                Row(verticalAlignment = Alignment.Top) {
                    row.forEach { action ->
                        QuickActionTile(
                            label = stringResource(action.label),
                            icon = action.icon,
                            accent = action.accent,
                            onClick = {
                                when {
                                    action.allTools -> onShowAllTools()
                                    action.tool != null -> onTool(action.tool)
                                    else -> onScan()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** A section title with an optional action at its end ("Recent documents · See all"). */
@Composable
internal fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PillHeight + Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.xs)
                .semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            QuietButton(text = actionLabel, onClick = onAction)
        }
    }
}

private const val TILES_PER_ROW = 4
private val MarkSize = 36.dp
private val PillHeight = 32.dp
