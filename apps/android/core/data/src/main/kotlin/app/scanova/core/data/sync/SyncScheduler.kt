package app.scanova.core.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Asks WorkManager to run [SyncWorker]. */
interface SyncScheduler {
    /** Something changed locally: sync as soon as there is a connection. */
    fun requestSync()

    /** Keeps the library fresh in the background while the user is signed in. */
    fun startPeriodicSync()

    /** Stops all scheduled sync work (sign-out, unpair). */
    fun cancelAll()
}

internal class WorkManagerSyncScheduler(
    private val context: Context,
) : SyncScheduler {

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // APPEND_OR_REPLACE: a request that arrives while a run is in progress is
        // queued behind it instead of being dropped, so the change that caused it
        // is never missed.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_ONE_TIME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun startPeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    private companion object {
        const val TAG = "scanova-sync"
        const val UNIQUE_ONE_TIME = "scanova-sync-now"
        const val UNIQUE_PERIODIC = "scanova-sync-periodic"
        const val BACKOFF_SECONDS = 30L
        const val PERIODIC_HOURS = 6L
    }
}
