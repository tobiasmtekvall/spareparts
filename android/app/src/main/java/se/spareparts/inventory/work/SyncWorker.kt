package se.spareparts.inventory.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import se.spareparts.inventory.SparePartsApp
import java.util.concurrent.TimeUnit

/** Sends the offline stock-change queue once the device has a network. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as SparePartsApp).container.repository
        val flushed = repo.flush()   // waits for the queue to be loaded from disk
        if (!flushed || repo.pending.value.isNotEmpty()) {
            return if (runAttemptCount < 20) Result.retry() else Result.failure()
        }
        repo.sync()
        return Result.success()
    }

    companion object {
        private const val NAME = "flush-stock-queue"

        fun schedule(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
