package app.scanova.core.data.sync

import app.scanova.core.common.log.ScanovaLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the sync engine, one run at a time. The background worker and a
 * pull-to-refresh from the library both come through here, so they can never
 * upload the same document concurrently.
 */
class SyncRunner internal constructor(
    private val engine: SyncEngine,
    /** Called after a run that brought phone and server fully in line. */
    private val onComplete: () -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun run(): SyncReport = mutex.withLock {
        val report = engine.run()
        if (report.outcome == SyncOutcome.COMPLETE) onComplete()
        ScanovaLog.info(TAG, "sync_finished outcome=${report.outcome}")
        report
    }

    /**
     * Runs [block] while no sync can run. Sign-out wipes the account's data this
     * way, so a sync already in progress cannot write that data back afterwards.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    private companion object {
        const val TAG = "Sync"
    }
}
