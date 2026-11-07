package io.github.beacon.app.runtime

import android.os.Parcel
import io.github.beacon.app.data.local.PollingWire
import io.github.beacon.app.data.local.TransportSecretStore
import io.github.beacon.app.domain.model.RuntimeEventKind
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.policy.RealtimeRetryDecision
import io.github.beacon.app.domain.policy.RealtimeRetryPolicy

/** IPC payload handling; invoked only after the service validates caller UID and descriptor. */
internal fun RuntimeProbeService.handleConnectionCommands(code: Int, data: Parcel, reply: Parcel?): Boolean {
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.RECONNECT_REALTIME) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val request =
            worker.submit<RealtimeRetryDecision> {
                val decision =
                    RealtimeRetryPolicy.decide(
                        realtime.plan,
                        pollingState,
                        TransportSecretStore(this@handleConnectionCommands).isConfigured(),
                    )
                if (decision == RealtimeRetryDecision.ALLOWED) {
                    if (
                        pollingState.blocked.isNotBlank() &&
                            pollingState.lastOutcome == SyncOutcome.TLS
                    )
                        saveState(pollingState.copy(blocked = "", config = pollingState.config.copy(enabled = true)))
                    recordEvent(RuntimeEventKind.RECONNECT_REQUESTED)
                    realtime.reset()
                    realtime.reconcile()
                }
                decision
            }
        val decision =
            try {
                request.get(45, java.util.concurrent.TimeUnit.SECONDS)
            } catch (error: java.util.concurrent.TimeoutException) {
                request.cancel(false)
                throw error
            }
        requireNotNull(reply).apply {
            writeNoException()
            writeInt(decision.wireId)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_CONNECTION) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
        generation.incrementAndGet()
        activeRuns.values.forEach { it.set(false) }
        val request =
            worker.submit<String> {
                saveState(
                    pollingState.copy(
                        config = pollingState.config.copy(enabled = false),
                        blocked = "",
                    )
                )
                realtime.reset()
                realtime.reconcile()
                TransportSecretStore(this@handleConnectionCommands).clear()
                recordEvent(RuntimeEventKind.CONNECTION_CLEARED)
                PollingWire.encode(pollingState)
            }
        val result =
            try {
                request.get(10, java.util.concurrent.TimeUnit.SECONDS)
            } catch (error: java.util.concurrent.TimeoutException) {
                request.cancel(false)
                throw error
            }
        requireNotNull(reply).apply {
            writeNoException()
            writeString(result)
        }
        return true
    }

    return false
}
