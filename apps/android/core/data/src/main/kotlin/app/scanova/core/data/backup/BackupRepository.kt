package app.scanova.core.data.backup

import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.sync.SyncScheduler
import app.scanova.core.security.store.EncryptedKeyValueStore
import app.scanova.core.security.store.TokenStore
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the backup is doing, for the library header and Settings. */
data class BackupStatus(
    /** Signed in: documents are backed up to the account and synced. */
    val enabled: Boolean,
    /** Documents on this phone that are not on the server yet (all of them while signed out). */
    val pendingCount: Int,
    /** When a backup last finished completely; null if never (or signed out). */
    val lastBackupAt: Instant?,
)

/**
 * Automatic backup = sync while signed in. The sync engine already uploads
 * every document made on this phone (including those made before signing in)
 * and pulls changes from other devices; WorkManager runs it after each change,
 * periodically, and when the network returns. This class reports its state and
 * lets the user start it now.
 */
class BackupRepository internal constructor(
    private val tokenStore: TokenStore,
    private val values: EncryptedKeyValueStore,
    documents: DocumentRepository,
    private val syncScheduler: SyncScheduler,
) {
    val status: Flow<BackupStatus> = combine(
        tokenStore.observeSignedIn(),
        documents.observePendingSyncCount(),
        values.observe(KEY_LAST_BACKUP),
    ) { signedIn, pending, last ->
        BackupStatus(
            enabled = signedIn,
            pendingCount = pending,
            lastBackupAt = if (signedIn) last?.toLongOrNull()?.let(Instant::ofEpochMilli) else null,
        )
    }.distinctUntilChanged()

    /** Back up now (and keep the periodic backup scheduled). Does nothing useful while signed out. */
    fun backUpNow() {
        if (tokenStore.current() == null) return
        syncScheduler.startPeriodicSync()
        syncScheduler.requestSync()
    }

    companion object {
        /** Epoch milliseconds of the last complete sync. Cleared on sign-out. */
        const val KEY_LAST_BACKUP = "backup.last_complete"
    }
}
