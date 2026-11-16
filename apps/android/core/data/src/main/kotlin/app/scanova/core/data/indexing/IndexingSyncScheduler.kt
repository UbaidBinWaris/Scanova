package app.scanova.core.data.indexing

import app.scanova.core.data.sync.SyncScheduler

/**
 * A [SyncScheduler] that also asks for text indexing whenever a sync is
 * requested. Every change that can leave pages without text or a document
 * without a type already requests a sync — a scan saved, an import, merge or
 * split, an edit, a restore continuing in the background, the backup at app
 * start — so wrapping the app's scheduler with this is the one place the
 * indexer needs to be wired:
 *
 * ```
 * fun provideSyncScheduler(@ApplicationContext context: Context, textIndex: TextIndexScheduler): SyncScheduler =
 *     IndexingSyncScheduler(WorkManagerSyncScheduler(context), textIndex)
 * ```
 *
 * Indexing never waits for sync and sync never waits for indexing; the two
 * are separate WorkManager jobs.
 */
class IndexingSyncScheduler(
    private val sync: SyncScheduler,
    private val textIndex: TextIndexScheduler,
) : SyncScheduler by sync {
    override fun requestSync() {
        sync.requestSync()
        textIndex.requestIndexing()
    }

    override fun cancelAll() {
        sync.cancelAll()
        textIndex.cancelAll()
    }
}
