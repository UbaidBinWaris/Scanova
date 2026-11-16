package app.scanova.core.data.indexing

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.security.keys.VaultUnrecoverableException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

/**
 * Background text indexing ([TextIndexer]): runs when [TextIndexScheduler]'s
 * battery-friendly constraints are met, stops (and later resumes) when they
 * no longer are. Works the same signed in or out: it only reads and writes
 * this phone's encrypted store and database.
 */
@HiltWorker
internal class TextIndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val indexer: TextIndexer,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            val report = indexer.runExclusive() ?: return Result.success()
            when {
                report.recognizerUnavailable -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                report.moreWork -> {
                    WorkManagerTextIndexScheduler.requestFollowUp(applicationContext)
                    Result.success()
                }
                else -> Result.success()
            }
        } catch (e: VaultUnrecoverableException) {
            // The keys are gone; the app resets itself on next launch.
            ScanovaLog.error(TAG, "indexing_aborted_keys_unavailable", e)
            Result.failure()
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "indexing_io_failure", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val TAG = "TextIndexWorker"
        const val MAX_ATTEMPTS = 5
    }
}
