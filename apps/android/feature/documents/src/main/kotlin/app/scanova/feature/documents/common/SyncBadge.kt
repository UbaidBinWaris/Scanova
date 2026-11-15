package app.scanova.feature.documents.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** Says in a word and an icon where the document stands between this phone and the server. */
@Composable
internal fun SyncBadge(document: Document, modifier: Modifier = Modifier) {
    when (document.syncState) {
        SyncState.LOCAL_ONLY -> StatusBadge(
            label = stringResource(R.string.feature_documents_sync_local),
            icon = Icons.Outlined.CloudQueue,
            tone = BadgeTone.Neutral,
            modifier = modifier,
        )
        SyncState.UPLOADING -> StatusBadge(
            label = stringResource(R.string.feature_documents_sync_uploading),
            icon = Icons.Outlined.CloudUpload,
            tone = BadgeTone.Neutral,
            modifier = modifier,
        )
        SyncState.SYNCED -> if (document.hasUnsyncedChanges) {
            StatusBadge(
                label = stringResource(R.string.feature_documents_sync_changes_pending),
                icon = Icons.Outlined.CloudQueue,
                tone = BadgeTone.Caution,
                modifier = modifier,
            )
        } else {
            StatusBadge(
                label = stringResource(R.string.feature_documents_sync_synced),
                icon = Icons.Outlined.CloudDone,
                tone = BadgeTone.Positive,
                modifier = modifier,
            )
        }
        SyncState.FAILED -> StatusBadge(
            label = stringResource(R.string.feature_documents_sync_failed),
            icon = Icons.Outlined.ErrorOutline,
            tone = BadgeTone.Critical,
            modifier = modifier,
        )
        // Deleted documents are never listed.
        SyncState.PENDING_DELETE -> Unit
    }
}

/** A date in the phone's own format and language. */
internal fun formatDate(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(instant.atZone(ZoneId.systemDefault()))
