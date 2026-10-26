package com.ferousco.hydro.monitoring

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/**
 * Owns the single periodic background monitoring job.
 *
 * Monitoring is intentionally conservative: it runs every 30 minutes, only with a network
 * connection, and under a unique work name so repeated app starts update the existing job instead of
 * stacking new ones. Android may defer the job (Doze, battery saver); Hydro never claims a check
 * happened when it did not.
 */
object ProactiveMonitorScheduler {

    /** One job name, so `ExistingPeriodicWorkPolicy.UPDATE` can keep it singular. */
    const val UNIQUE_WORK_NAME = "hydro_proactive_monitor"

    /**
     * WorkManager's floor for periodic work is 15 minutes. Thirty minutes trades a little
     * responsiveness for meaningfully less radio and battery use; the deterministic thresholds in
     * `ProactiveDecisionEngine` are slow-moving enough that this is still useful.
     */
    val INTERVAL: Duration = Duration.ofMinutes(30)

    /** Lets the OS batch the job inside the interval rather than waking the device precisely. */
    val FLEX: Duration = Duration.ofMinutes(10)

    /** Retries after a failed network/provider call back off instead of hammering the API. */
    val BACKOFF: Duration = Duration.ofMinutes(15)

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request()
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /** Exposed for tests and for callers that need the exact request shape. */
    fun request(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<ProactiveMonitorWorker>(INTERVAL, FLEX)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
            .addTag(UNIQUE_WORK_NAME)
            .build()
}
