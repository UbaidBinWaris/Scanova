package app.scanova.feature.documents.library

import app.scanova.core.data.backup.BackupStatus
import java.time.Instant

/** What the top of the library says about backup: a card while signed out, one quiet line while signed in. */
internal sealed interface BackupSummary {
    /** No account: documents exist only on this phone. */
    data object Off : BackupSummary

    /** Everything on this phone is backed up. [lastBackupAt] is null until the first complete backup. */
    data class UpToDate(val lastBackupAt: Instant?) : BackupSummary

    data class BackingUp(val count: Int) : BackupSummary

    /** Documents wait for the phone to come online. */
    data class WaitingForConnection(val count: Int) : BackupSummary

    /** The server refused [count] documents; each one offers "Try again" in the viewer. */
    data class Failed(val count: Int) : BackupSummary
}

/**
 * Folds the backup status, the number of refused documents and the network state into one summary.
 * [BackupStatus.pendingCount] includes refused documents, which are not "backing up".
 */
internal fun backupSummary(status: BackupStatus, failedCount: Int, online: Boolean): BackupSummary {
    if (!status.enabled) return BackupSummary.Off
    val waiting = (status.pendingCount - failedCount).coerceAtLeast(0)
    return when {
        waiting > 0 && !online -> BackupSummary.WaitingForConnection(waiting)
        failedCount > 0 -> BackupSummary.Failed(failedCount)
        waiting > 0 -> BackupSummary.BackingUp(waiting)
        else -> BackupSummary.UpToDate(status.lastBackupAt)
    }
}
