package io.github.beacon.app.runtime

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.core.currentProcessName
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.receive.PollingSchedulePolicy
import io.github.beacon.app.domain.receive.ScheduleAction
import java.util.concurrent.TimeUnit

/** Called only in the main process; durable control is always sourced from :runtime. */
object PollingScheduler {
    private const val PERIODIC = "beacon.http.periodic.v1"
    private const val TEST_ONCE = "beacon.http.test.once.v1"
    private var appliedState: PollingState? = null

    private fun constraints(config: PollingConfig) =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (config.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(config.charging)
            .build()

    @Synchronized
    fun apply(context: Context, state: PollingState) {
        check(currentProcessName(context) == context.packageName)
        val action = PollingSchedulePolicy.decide(appliedState, state)
        // A refreshed result changes revision, not the schedule. Do not postpone the next run.
        if (action == ScheduleAction.KEEP) {
            if (state.revision >= (appliedState?.revision ?: -1)) appliedState = state
            return
        }
        val manager = WorkManager.getInstance(context)
        if (action == ScheduleAction.ENABLE) {
            val request =
                PeriodicWorkRequestBuilder<HttpPollingWorker>(
                        state.config.intervalMinutes.toLong(),
                        TimeUnit.MINUTES,
                    )
                    .setInitialDelay(state.config.intervalMinutes.toLong(), TimeUnit.MINUTES)
                    .setConstraints(constraints(state.config))
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        kotlin.random.Random.nextLong(30, 61),
                        TimeUnit.SECONDS,
                    )
                    .build()
            manager
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
                .result
                .get(10, TimeUnit.SECONDS)
        } else {
            manager.cancelUniqueWork(PERIODIC).result.get(10, TimeUnit.SECONDS)
            manager.cancelUniqueWork(TEST_ONCE).result.get(10, TimeUnit.SECONDS)
        }
        // Commit only after WorkManager confirms; failed operations remain retryable.
        appliedState = state
    }

    @Synchronized
    fun testOnce(context: Context, state: PollingState) {
        check(BuildConfig.DEBUG && state.shouldSchedule)
        // Debug-only one-shot probe. WorkManager periodic work remains 15 minutes minimum.
        val request =
            OneTimeWorkRequestBuilder<HttpPollingWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(constraints(state.config))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TEST_ONCE, ExistingWorkPolicy.KEEP, request)
            .result
            .get(10, TimeUnit.SECONDS)
    }
}
