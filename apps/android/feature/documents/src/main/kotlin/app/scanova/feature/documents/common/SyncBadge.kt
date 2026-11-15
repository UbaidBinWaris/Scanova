package app.scanova.feature.documents.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.scanova.core.data.model.Document
import app.scanova.core.data.model.SyncState
import app.scanova.core.designsystem.component.BadgeTone
import app.scanova.core.designsystem.component.StatusBadge
import app.scanova.feature.documents.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The backup states worth a badge. */
internal enum class SyncBadgeKind { NotBackedUp, BackingUp, ChangesPending, BackedUp, Failed }

/**
 * Which badge a document gets, or null when there is nothing worth saying.
 * Without an account nothing is expected to be backed up, so only a refusal is
 * shown. A document that is backed up gets a badge only where [showBackedUp]
 * asks for it (the viewer); in the library, silence means "backed up".
 */
internal fun syncBadgeKind(document: Document, signedIn: Boolean, showBackedUp: Boolean = false): SyncBadgeKind? =
    when (document.syncState) {
        SyncState.FAILED -> SyncBadgeKind.Failed
        // Deleted documents are never listed.
        SyncState.PENDING_DELETE -> null
        else -> if (!signedIn) {
            null
        } else {
            when (document.syncState) {
                SyncState.LOCAL_ONLY -> SyncBadgeKind.NotBackedUp
                SyncState.UPLOADING -> SyncBadgeKind.BackingUp
                else -> when {
                    document.hasUnsyncedChanges -> SyncBadgeKind.ChangesPending
                    showBackedUp -> SyncBadgeKind.BackedUp
                    else -> null
                }
            }
        }
    }

/** Says in a word and an icon where the document stands with the backup. Draws nothing for a null [kind]. */
@Composable
internal fun SyncBadge(kind: SyncBadgeKind?, modifier: Modifier = Modifier) {
    when (kind) {
        null -> Unit
        SyncBadgeKind.NotBackedUp -> StatusBadge(
            label = stringResource(R.string.feature_documents_sync_local),
            icon = Icons.Outlined.CloudQueue,
            tone = BadgeTone.Neutral,
            modifier = modifier,
        )
        SyncBadgeKind.BackingUp -> StatusBadge(
            label = stringResource(R.string.feature_documents_sync_uploading),
            icon = Icons.Outlined.CloudUpload,
            tone = BadgeTone.Neutral,
            modifier = modifier,
        )
        SyncBadgeKind.ChangesPending -> StatusBadge(
            label = stringResource(R.string.feature_documents_sync_changes_pending),
            icon = Icons.Outlined.CloudQueue,
            tone = BadgeTone.Caution,
            modifier = modifier,
        )
        SyncBadgeKind.BackedUp -> StatusBadge(
            label = stringResource(R.string.feature_documents_sync_synced),
            icon = Icons.Outlined.CloudDone,
            tone = BadgeTone.Positive,
            modifier = modifier,
        )
        SyncBadgeKind.Failed -> StatusBadge(
            label = stringResource(R.string.feature_documents_sync_failed),
            icon = Icons.Outlined.ErrorOutline,
            tone = BadgeTone.Critical,
            modifier = modifier,
        )
    }
}

/** A date in the phone's own format and language. */
internal fun formatDate(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(instant.atZone(ZoneId.systemDefault()))

/** "12 Nov 2026 · 3 pages", the line under a document's title. */
@Composable
internal fun documentDetails(document: Document): String = stringResource(
    R.string.feature_documents_item_details,
    formatDate(document.capturedAt),
    pluralStringResource(R.plurals.feature_documents_page_count, document.pageCount, document.pageCount),
)
