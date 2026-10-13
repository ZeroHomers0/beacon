package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.RealtimeConfiguration
import java.io.IOException

internal class RealtimeAuthorizationFailure : IOException("Realtime authorization rejected")

internal interface RealtimeTransport : AutoCloseable {
    interface Listener {
        fun lost(error: Throwable)

        fun message(payload: ByteArray)
    }

    fun connect(config: RealtimeConfiguration, password: String, listener: Listener)

    fun ready()

    val heartbeatSeconds: Int
        get() = io.github.beacon.app.domain.model.RealtimeHeartbeat.DEFAULT_SECONDS

    val longestHeartbeatSeconds: Int
        get() = heartbeatSeconds

    fun coalesceHeartbeat() {}
}
