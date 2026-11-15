package app.scanova.feature.documents.common

import app.scanova.core.data.backup.BackupStatus
import app.scanova.core.data.model.Document
import app.scanova.core.data.model.SyncState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupSummaryTest {

    private val last = Instant.parse("2026-11-15T10:00:00Z")

    @Test
    fun signedOutIsOffWhateverIsPending() {
        val status = BackupStatus(enabled = false, pendingCount = 4, lastBackupAt = null)
        assertEquals(BackupSummary.Off, backupSummary(status, failedCount = 0, online = false))
    }

    @Test
    fun nothingPendingIsUpToDate() {
        val status = BackupStatus(enabled = true, pendingCount = 0, lastBackupAt = last)
        assertEquals(BackupSummary.UpToDate(last), backupSummary(status, failedCount = 0, online = false))
    }

    @Test
    fun pendingWhileOnlineIsBackingUp() {
        val status = BackupStatus(enabled = true, pendingCount = 3, lastBackupAt = last)
        assertEquals(BackupSummary.BackingUp(3), backupSummary(status, failedCount = 0, online = true))
    }

    @Test
    fun pendingWhileOfflineWaitsForAConnection() {
        val status = BackupStatus(enabled = true, pendingCount = 2, lastBackupAt = last)
        assertEquals(BackupSummary.WaitingForConnection(2), backupSummary(status, failedCount = 0, online = false))
    }

    @Test
    fun refusedDocumentsAreNotCountedAsBackingUp() {
        val status = BackupStatus(enabled = true, pendingCount = 1, lastBackupAt = last)
        assertEquals(BackupSummary.Failed(1), backupSummary(status, failedCount = 1, online = true))
        // Offline with only refused documents left: nothing is waiting for the network.
        assertEquals(BackupSummary.Failed(1), backupSummary(status, failedCount = 1, online = false))
    }

    @Test
    fun badgesOnlyWhenTheyMeanSomething() {
        assertNull(syncBadgeKind(document(SyncState.LOCAL_ONLY), signedIn = false))
        assertEquals(SyncBadgeKind.NotBackedUp, syncBadgeKind(document(SyncState.LOCAL_ONLY), signedIn = true))
        assertEquals(SyncBadgeKind.BackingUp, syncBadgeKind(document(SyncState.UPLOADING), signedIn = true))
        assertNull(syncBadgeKind(document(SyncState.SYNCED), signedIn = true))
        assertEquals(SyncBadgeKind.BackedUp, syncBadgeKind(document(SyncState.SYNCED), signedIn = true, showBackedUp = true))
        assertEquals(
            SyncBadgeKind.ChangesPending,
            syncBadgeKind(document(SyncState.SYNCED, dirty = true), signedIn = true),
        )
        assertEquals(SyncBadgeKind.Failed, syncBadgeKind(document(SyncState.FAILED), signedIn = false))
        assertNull(syncBadgeKind(document(SyncState.PENDING_DELETE), signedIn = true))
    }

    private fun document(state: SyncState, dirty: Boolean = false) = Document(
        id = "d1",
        title = "Receipt",
        tags = emptyList(),
        pageCount = 1,
        sizeBytes = null,
        capturedAt = last,
        updatedAt = last,
        syncState = state,
        hasUnsyncedChanges = dirty,
        syncError = null,
        storedOnDevice = true,
    )
}
