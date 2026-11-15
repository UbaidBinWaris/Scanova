package app.scanova.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.security.keys.VaultUnrecoverableException
import app.scanova.core.security.store.TokenStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

/**
 * Background synchronisation. WorkManager starts it when the network
 * constraint is met and re-runs it with exponential backoff after
 * [Result.retry], which is how uploads survive bad connectivity, a busy server
 * and the app being closed.
 */
@HiltWorker
internal class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val runner: SyncRunner,
    private val tokenStore: TokenStore,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            // Signed out: nothing may be sent. Work is scheduled again on sign-in.
            if (tokenStore.current() == null) return Result.success()
            when (runner.run().outcome) {
                SyncOutcome.COMPLETE -> Result.success()
                SyncOutcome.AUTH_REQUIRED -> Result.success()
                SyncOutcome.RETRY_LATER -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        } catch (e: VaultUnrecoverableException) {
            // The keys are gone; the app resets itself on next launch. Retrying cannot help.
            ScanovaLog.error(TAG, "sync_aborted_keys_unavailable", e)
            Result.failure()
        } catch (e: IOException) {
            ScanovaLog.warn(TAG, "sync_io_failure", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val TAG = "SyncWorker"

        // With 30 s exponential backoff this spans several hours; the periodic
        // sync and the next local change start a fresh chain afterwards.
        const val MAX_ATTEMPTS = 10
    }
}
