package io.github.beacon.app.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.beacon.app.data.local.PollingWire
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.receive.PollingPolicy
import io.github.beacon.app.ipc.RuntimeProtocol
import io.github.beacon.app.ipc.request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Main-process scheduler delegates network/secret/SQLite work to the same :runtime service. */
open class HttpPollingWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    @Volatile private var endpoint: IBinder? = null
    private val stopExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun stopRemote() {
        val binder = endpoint ?: return
        try {
            // The reply carries the polling state, which this path does not act on: the
            // request helper still consumes it so the payload check stays meaningful.
            binder.request(RuntimeProtocol.STOP_RUN, write = { it.writeString(id.toString()) }) {
                it.readString()
            }
        } catch (_: Exception) {
            /* The bounded operation may already have exited. */
        }
    }

    override fun onStopped() {
        super.onStopped()
        try {
            stopExecutor.execute(::stopRemote)
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    override fun doWork(): Result {
        val connected = CountDownLatch(1)
        var bound = false
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    endpoint = service
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    endpoint = null
                    connected.countDown()
                }

                override fun onNullBinding(name: ComponentName) {
                    connected.countDown()
                }

                override fun onBindingDied(name: ComponentName) {
                    endpoint = null
                    connected.countDown()
                }
            }
        try {
            if (isStopped) return Result.success()
            bound =
                applicationContext.bindService(
                    Intent(applicationContext, RuntimeProbeService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            if (!bound || !connected.await(10, TimeUnit.SECONDS))
                return retryOrFinish(SyncOutcome.TRANSIENT)
            val binder = endpoint ?: return retryOrFinish(SyncOutcome.TRANSIENT)
            if (isStopped) return Result.success()
            val (state, outcome) =
                binder.request(
                    RuntimeProtocol.AUTO_SYNC,
                    write = { it.writeString(id.toString()) },
                ) {
                    it.readString() // Sanitized summary is retained in runtime control.
                    val decoded = PollingWire.decode(requireNotNull(it.readString()))
                    // Decoded by wireId, not ordinal: enum order is not the wire contract.
                    val result =
                        requireNotNull(SyncOutcome.fromWireId(it.readInt())) {
                            "Unknown sync outcome"
                        }
                    decoded to result
                }
            if (!state.shouldSchedule) PollingScheduler.apply(applicationContext, state)
            return if (isStopped) Result.success() else retryOrFinish(outcome)
        } catch (_: Exception) {
            return if (isStopped) Result.success() else retryOrFinish(SyncOutcome.TRANSIENT)
        } finally {
            stopRemote()
            if (bound)
                try {
                    applicationContext.unbindService(connection)
                } catch (_: IllegalArgumentException) {}
            endpoint = null
            stopExecutor.shutdown()
        }
    }

    private fun retryOrFinish(outcome: SyncOutcome): Result =
        if (PollingPolicy.retry(outcome, runAttemptCount)) Result.retry() else Result.success()
}
