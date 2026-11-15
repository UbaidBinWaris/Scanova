package app.scanova.feature.documents.library

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.scanova.core.data.model.Document
import app.scanova.core.data.settings.LibraryLayout
import app.scanova.core.designsystem.component.IconTile
import app.scanova.core.designsystem.component.PrimaryButton
import app.scanova.core.designsystem.component.QuietButton
import app.scanova.core.designsystem.component.ScanovaCard
import app.scanova.core.designsystem.component.SecondaryButton
import app.scanova.core.designsystem.theme.Spacing
import app.scanova.core.designsystem.theme.StatusColors
import app.scanova.feature.documents.R
import app.scanova.feature.documents.common.SyncBadge
import app.scanova.feature.documents.common.documentDetails
import app.scanova.feature.documents.common.syncBadgeKind
import kotlinx.coroutines.delay

// ── Backup ──────────────────────────────────────────────────────────────

/** Signed out: a calm card offering an account. Signed in: one quiet status line. */
@Composable
internal fun BackupHeader(
    summary: BackupSummary,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summary == BackupSummary.Off) {
        BackupOffCard(onSignIn = onSignIn, onCreateAccount = onCreateAccount, modifier = modifier)
    } else {
        BackupStatusLine(summary = summary, modifier = modifier)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupOffCard(onSignIn: () -> Unit, onCreateAccount: () -> Unit, modifier: Modifier = Modifier) {
    ScanovaCard(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            IconTile(icon = Icons.Outlined.PhoneAndroid, size = TileSize)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(R.string.feature_documents_backup_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.feature_documents_backup_off_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        // Lined up under the text; wraps when a translation or a large font needs the room.
        FlowRow(
            modifier = Modifier.padding(start = TileSize + Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            SecondaryButton(text = stringResource(R.string.feature_documents_backup_create_account), onClick = onCreateAccount)
            QuietButton(text = stringResource(R.string.feature_documents_backup_sign_in), onClick = onSignIn)
        }
    }
}

private enum class LineTone { Positive, Neutral, Caution, Critical }

@Composable
private fun BackupStatusLine(summary: BackupSummary, modifier: Modifier = Modifier) {
    val icon: ImageVector
    val tone: LineTone
    val text: String
    when (summary) {
        // Not shown as a line; handled by the card.
        BackupSummary.Off -> return
        is BackupSummary.UpToDate -> {
            icon = Icons.Outlined.CloudDone
            tone = LineTone.Positive
            text = backedUpText(summary)
        }
        is BackupSummary.BackingUp -> {
            icon = Icons.Outlined.CloudSync
            tone = LineTone.Neutral
            text = pluralStringResource(R.plurals.feature_documents_backup_running, summary.count, summary.count)
        }
        is BackupSummary.WaitingForConnection -> {
            icon = Icons.Outlined.CloudOff
            tone = LineTone.Caution
            text = pluralStringResource(R.plurals.feature_documents_backup_offline, summary.count, summary.count)
        }
        is BackupSummary.Failed -> {
            icon = Icons.Outlined.ErrorOutline
            tone = LineTone.Critical
            text = pluralStringResource(R.plurals.feature_documents_backup_failed, summary.count, summary.count)
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.xxl)
            .padding(horizontal = Spacing.xs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = toneColor(tone), modifier = Modifier.size(StatusIconSize))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Backed up 5 minutes ago", kept current while the screen is open. */
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
        last == null -> stringResource(R.string.feature_documents_backup_on)
        now - last < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.feature_documents_backup_done_now)
        else -> stringResource(
            R.string.feature_documents_backup_done_ago,
            DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    }
}

@Composable
private fun toneColor(tone: LineTone): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (tone) {
        LineTone.Positive -> if (dark) StatusColors.positiveDark else StatusColors.positiveLight
        LineTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        LineTone.Caution -> if (dark) StatusColors.cautionDark else StatusColors.cautionLight
        LineTone.Critical -> MaterialTheme.colorScheme.error
    }
}

// ── Documents ───────────────────────────────────────────────────────────

@Composable
internal fun DocumentRow(
    document: Document,
    signedIn: Boolean,
    loadCover: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScanovaCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Cover(
                documentId = document.id,
                loadCover = loadCover,
                placeholderIconSize = Spacing.xl,
                modifier = Modifier
                    .size(width = ThumbnailWidth, height = ThumbnailHeight)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .border(hairline(), MaterialTheme.shapes.extraSmall),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = documentDetails(document),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SyncBadge(syncBadgeKind(document, signedIn), Modifier.padding(top = Spacing.xs))
            }
        }
    }
}

@Composable
internal fun DocumentCell(
    document: Document,
    signedIn: Boolean,
    loadCover: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScanovaCard(onClick = onClick, modifier = modifier, contentPadding = PaddingValues()) {
        Box {
            Cover(
                documentId = document.id,
                loadCover = loadCover,
                placeholderIconSize = Spacing.xxl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CELL_COVER_RATIO),
            )
            // On the cover, so every cell in a row keeps the same height.
            SyncBadge(syncBadgeKind(document, signedIn), Modifier.padding(Spacing.sm))
        }
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = documentDetails(document),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The document's first page when it was scanned on this phone, otherwise a
 * document symbol. Decorative: the title next to it names the document.
 */
@Composable
private fun Cover(
    documentId: String,
    loadCover: suspend (String) -> Bitmap?,
    placeholderIconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val cover by produceState<Bitmap?>(initialValue = null, documentId) { value = loadCover(documentId) }
    Crossfade(
        targetState = cover,
        animationSpec = tween(MOTION_MILLIS),
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        label = "cover",
    ) { bitmap ->
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // The top of a page usually says what it is.
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(placeholderIconSize),
                )
            }
        }
    }
}

@Composable
private fun hairline() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

// ── States ──────────────────────────────────────────────────────────────

/**
 * Placeholder rows or cells while the library opens. Reading the local database
 * usually takes a moment only, so the skeleton waits briefly before it appears.
 */
@Composable
internal fun LibrarySkeleton(layout: LibraryLayout) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SKELETON_DELAY_MILLIS)
        visible = true
    }
    val description = stringResource(R.string.feature_documents_loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(if (layout == LibraryLayout.LIST) Spacing.sm else Spacing.md),
    ) {
        if (!visible) return@Column
        if (layout == LibraryLayout.LIST) {
            repeat(SKELETON_ROWS) { SkeletonRow() }
        } else {
            repeat(SKELETON_ROWS / 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SkeletonCell(Modifier.weight(1f))
                    SkeletonCell(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SkeletonRow() {
    ScanovaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Bone(Modifier.size(width = ThumbnailWidth, height = ThumbnailHeight))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Bone(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(Spacing.md),
                )
                Bone(
                    Modifier
                        .fillMaxWidth(0.35f)
                        .height(Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun SkeletonCell(modifier: Modifier = Modifier) {
    ScanovaCard(modifier = modifier, contentPadding = PaddingValues()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CELL_COVER_RATIO)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Bone(
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(Spacing.md),
            )
            Bone(
                Modifier
                    .fillMaxWidth(0.45f)
                    .height(Spacing.sm),
            )
        }
    }
}

@Composable
private fun Bone(modifier: Modifier) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** Nothing scanned yet. The scan button here is the screen's one primary action. */
@Composable
internal fun EmptyLibrary(signedIn: Boolean, onScan: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = Icons.Outlined.DocumentScanner, size = EmptyTileSize)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.feature_documents_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(
                if (signedIn) R.string.feature_documents_empty_body_backup else R.string.feature_documents_empty_body_local,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = ReadableWidth),
        )
        Spacer(Modifier.height(Spacing.xl))
        PrimaryButton(
            text = stringResource(R.string.feature_documents_scan_first),
            onClick = onScan,
            icon = Icons.Outlined.DocumentScanner,
        )
    }
}

/** A search that matched nothing. Sits high on the screen, above the keyboard. */
@Composable
internal fun NoResults(onClearSearch: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon = Icons.Outlined.SearchOff, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = NoResultsTileSize)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.feature_documents_no_results_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.feature_documents_no_results_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = ReadableWidth),
        )
        Spacer(Modifier.height(Spacing.md))
        QuietButton(text = stringResource(R.string.feature_documents_search_clear), onClick = onClearSearch)
    }
}

private const val SKELETON_DELAY_MILLIS = 150L
private const val SKELETON_ROWS = 6

/** Grid covers show the top of the page, a little shorter than paper. */
private const val CELL_COVER_RATIO = 4f / 5f

private val ThumbnailWidth = 48.dp
private val ThumbnailHeight = 64.dp
private val TileSize = 40.dp
private val EmptyTileSize = 64.dp
private val NoResultsTileSize = 56.dp
private val StatusIconSize = 16.dp

/** Keeps centred text at a comfortable line length. */
private val ReadableWidth = 360.dp
