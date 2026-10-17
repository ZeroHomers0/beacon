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
import com.google.common.util.concurrent.ListenableFuture
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.core.currentProcessName
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.receive.PollingSchedulePolicy
import io.github.beacon.app.domain.receive.ScheduleAction
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies the reception schedule to WorkManager.
 *
 * Called only in the communication process; durable control is always sourced
 * from the reception service.
 *
 * Every WorkManager call returns a future rather than blocking until the system
 * has accepted the request. The previous implementation waited up to ten seconds
 * per call on the service's scheduler thread, and `saveState()` reconciles on
 * every state change, so a slow WorkManager could queue up waits faster than they
 * completed. The apply is now single-flight: at most one operation is outstanding,
 * intermediate requests coalesce into the newest state, and the decision is made
 * when the previous operation reports back.
 */
object PollingScheduler {
    private const val PERIODIC = "beacon.http.periodic.v1"
    private const val TEST_ONCE = "beacon.http.test.once.v1"

    private val directExecutor = Executor { it.run() }

    private var appliedState: PollingState? = null
    private var inFlight = false
    private var pending: PollingState? = null

    private fun constraints(config: PollingConfig) =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (config.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(config.charging)
            .build()

    @Synchronized
    fun apply(context: Context, state: PollingState) {
        check(currentProcessName(context) == context.packageName + ":runtime")
        pending = state
        submit(context)
    }

    /** Debug-only one-shot probe; WorkManager periodic work remains 15 minutes minimum. */
    @Synchronized
    fun testOnce(context: Context, state: PollingState) {
        check(currentProcessName(context) == context.packageName + ":runtime")
        check(BuildConfig.DEBUG && state.shouldSchedule)
        val request =
            OneTimeWorkRequestBuilder<HttpPollingWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(constraints(state.config))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        val future =
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TEST_ONCE, ExistingWorkPolicy.KEEP, request)
                .result
        observe(future, onSuccess = {}, onFailure = {})
    }

    /**
     * Runs the next queued decision unless one is already outstanding. Must be
     * called while holding the monitor.
     */
    private fun submit(context: Context) {
        if (inFlight) return
        val state = pending ?: return
        pending = null
        val action = PollingSchedulePolicy.decide(appliedState, state)
        // A refreshed result changes revision, not the schedule. Do not postpone the next run.
        if (action == ScheduleAction.KEEP) {
            if (state.revision >= (appliedState?.revision ?: -1)) appliedState = state
            submit(context)
            return
        }
        val manager = WorkManager.getInstance(context)
        inFlight = true
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
            val future =
                manager
                    .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
                    .result
            observe(
                future,
                onSuccess = { complete(context, state) },
                onFailure = { complete(context, null) },
            )
        } else {
            // Both names must be gone; the one-shot probe only exists in debug builds.
            val periodic = manager.cancelUniqueWork(PERIODIC).result
            val testOnce = manager.cancelUniqueWork(TEST_ONCE).result
            observe(
                testOnce,
                onSuccess = {
                    observe(
                        periodic,
                        onSuccess = { complete(context, state) },
                        onFailure = { complete(context, null) },
                    )
                },
                onFailure = { complete(context, null) },
            )
        }
    }

    /**
     * Commits [confirmed] as the applied state, or leaves the previous one in place
     * so a failed operation stays retryable on the next reconcile.
     */
    @Synchronized
    private fun complete(context: Context, confirmed: PollingState?) {
        if (confirmed != null) appliedState = confirmed
        inFlight = false
        submit(context)
    }

    private fun observe(
        future: ListenableFuture<*>,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val settled = AtomicBoolean(false)
        future.addListener(
            {
                if (settled.compareAndSet(false, true)) {
                    if (runCatching { future.get() }.isSuccess) onSuccess() else onFailure()
                }
            },
            directExecutor,
        )
    }
}
