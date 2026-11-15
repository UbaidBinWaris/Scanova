package app.scanova.core.data.storage

import android.content.Context
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.data.sync.SyncRunner
import app.scanova.core.data.sync.SyncScheduler
import app.scanova.core.database.ScanovaDatabase
import app.scanova.core.security.LocalVault
import kotlinx.coroutines.withContext

/** Erases local data when an account or a pairing ends. */
class LocalDataCleaner internal constructor(
    private val context: Context,
    private val database: ScanovaDatabase,
    private val vault: LocalVault,
    private val syncScheduler: SyncScheduler,
    private val exports: ExportFiles,
    private val dispatchers: AppDispatchers,
    private val syncRunner: SyncRunner,
) {
    /**
     * Removes everything that belongs to the signed-in account: documents,
     * pages, files and sync bookkeeping. The pairing and the app settings stay.
     * Runs under the sync lock, so a sync that was mid-way cannot put any of the
     * account's titles, tags or pull cursor back after the wipe.
     */
    suspend fun wipeAccountData() {
        syncScheduler.cancelAll()
        syncRunner.exclusive {
            database.documentDao().deleteAll()
            database.syncMetaDao().deleteAll()
            withContext(dispatchers.io) {
                vault.deleteAllFiles()
                exports.deleteAll()
            }
        }
    }

    /**
     * Deletes plaintext share copies left by a share that never returned (the process
     * died, or the app was signed out on another screen). Run at every start.
     */
    suspend fun deleteShareCopies() = withContext(dispatchers.io) {
        exports.deleteAll()
    }

    /** Removes the account data and the pairing, tokens and settings: a factory-fresh app. */
    suspend fun wipeEverything() {
        wipeAccountData()
        withContext(dispatchers.io) { vault.deleteAllValues() }
    }

    /**
     * Last resort when the stored keys cannot be unwrapped any more (the
     * Keystore key was lost, for example after the phone was restored from a
     * backup). Nothing encrypted can be read again, so it is all deleted and
     * the app starts from scratch. Must run before the database is opened.
     */
    suspend fun resetAfterKeyLoss() = withContext(dispatchers.io) {
        ScanovaLog.error(TAG, "local_keys_unrecoverable_resetting")
        syncScheduler.cancelAll()
        runCatching { database.close() }
        context.deleteDatabase(ScanovaDatabase.FILE_NAME)
        vault.deleteAllFiles()
        vault.deleteAllValues()
        vault.destroyKeys()
        exports.deleteAll()
    }

    private companion object {
        const val TAG = "LocalData"
    }
}
