package com.visuallearn.app.figures

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.visuallearn.app.VisualLearnApp
import java.util.concurrent.TimeUnit

/**
 * Figures take 20 to 75 minutes, and Android may stop the app meanwhile. While any figure
 * is unfinished, this syncs every 15 minutes so finished ones are downloaded and announced
 * even with the app closed. (Push through Firebase would be quicker; it needs a project.)
 */
class FigureCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as VisualLearnApp
        if (!app.tokens.signedIn.value) return Result.success()
        return if (app.sync.syncOnce()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "figure-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FigureCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
