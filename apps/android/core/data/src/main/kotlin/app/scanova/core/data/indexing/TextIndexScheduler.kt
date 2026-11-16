package app.scanova.core.data.indexing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Asks WorkManager to run the background text indexer ([TextIndexWorker]). */
interface TextIndexScheduler {
    /**
     * Pages may need their text read, or documents a type and title (after a
     * scan is saved, an import, merge, split, restore, or at app start): index
     * soon, once the battery is not low. Cheap and idempotent: call it freely.
     */
    fun requestIndexing()

    /** Stops all indexing work (sign-out erases the documents anyway). */
    fun cancelAll()
}

/**
 * - A request runs within seconds when the battery is not low (KEEP: a run
 *   that is already queued or running reads everything new anyway, because it
 *   keeps querying until nothing is left).
 * - A run that used up its budget queues a follow-up that waits for the phone
 *   to be charging: a big backlog (a restore, the first run after an update)
 *   is worked off on the charger, never on battery.
 * - A periodic run (every 12 hours, charging) catches anything a request missed.
 */
internal class WorkManagerTextIndexScheduler(private val context: Context) : TextIndexScheduler {

    override fun requestIndexing() {
        val request = OneTimeWorkRequestBuilder<TextIndexWorker>()
            .setConstraints(batteryFriendly(charging = false))
            .setInitialDelay(START_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
        val periodic = PeriodicWorkRequestBuilder<TextIndexWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(batteryFriendly(charging = true))
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    companion object {
        private const val TAG = "scanova-text-index"
        private const val UNIQUE_NOW = "scanova-text-index-now"
        private const val UNIQUE_PERIODIC = "scanova-text-index-periodic"
        private const val START_DELAY_SECONDS = 3L
        private const val BACKOFF_SECONDS = 60L
        private const val PERIODIC_HOURS = 12L
        private const val FOLLOW_UP_DELAY_SECONDS = 30L

        private fun batteryFriendly(charging: Boolean): Constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiresCharging(charging)
            .build()

        /** Called by a run that left work over: continue while charging, after this run has finished. */
        internal fun requestFollowUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<TextIndexWorker>()
                .setConstraints(batteryFriendly(charging = true))
                .setInitialDelay(FOLLOW_UP_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            // Appended: the run asking for it is still in progress under the same name.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
