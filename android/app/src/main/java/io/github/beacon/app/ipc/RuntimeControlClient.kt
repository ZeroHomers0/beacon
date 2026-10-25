package io.github.beacon.app.ipc

import android.os.IBinder
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.data.local.PollingWire
import io.github.beacon.app.domain.model.BindingRequest
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.QuietHours
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.model.TransportSettings

internal data class RuntimeControlReply(
    val text: String,
    val state: PollingState,
    val outcome: SyncOutcome,
)

internal class RuntimeControlClient(private val binder: IBinder) {
    fun barkEncryptionConfigured(): Boolean =
        binder.request(RuntimeProtocol.BARK_ENCRYPTION_STATUS) { it.readInt() == 1 }

    fun barkEncryption(key: String, iv: String): String =
        binder.request(
            RuntimeProtocol.BARK_ENCRYPTION,
            write = {
                require(key.length <= 64 && iv.length <= 64)
                it.writeString(key)
                it.writeString(iv)
            },
        ) {
            requireNotNull(it.readString())
        }

    fun testDelivery(): String =
        binder.request(RuntimeProtocol.TEST_DELIVERY) { requireNotNull(it.readString()) }

    fun historyArchive(
        uri: String,
        password: String,
        importing: Boolean,
        encrypted: Boolean,
    ): String =
        binder.request(
            RuntimeProtocol.HISTORY_ARCHIVE,
            write = {
                require(uri.length <= 4096 && password.length <= 256)
                it.writeString(uri)
                it.writeString(password)
                it.writeInt(if (importing) 1 else 0)
                it.writeInt(if (encrypted) 1 else 0)
            },
        ) {
            requireNotNull(it.readString())
        }

    fun settingsBackup(payload: String?): String =
        binder.request(
            if (payload == null) RuntimeProtocol.EXPORT_SETTINGS
            else RuntimeProtocol.IMPORT_SETTINGS,
            write = {
                if (payload != null) {
                    require(payload.length <= 4096)
                    it.writeString(payload)
                }
            },
        ) {
            requireNotNull(it.readString()).also { result -> require(result.length <= 4096) }
        }

    fun temporaryMute(scope:io.github.beacon.app.domain.model.MuteScope,untilMs:Long?=null):io.github.beacon.app.domain.model.MuteSnapshot {
        scope.validate();require(untilMs==null||untilMs>=0)
        return binder.request(RuntimeProtocol.TEMPORARY_MUTE,write={
            it.writeString(scope.kind.name);it.writeString(scope.source);it.writeString(scope.identity)
            it.writeInt(if(untilMs==null)0 else 1);untilMs?.let{until->it.writeLong(until)}
        }) {io.github.beacon.app.domain.model.MuteSnapshot(it.readLong(),it.readLong())}
    }

    fun notificationSettings(value: NotificationSettings?): NotificationSettings =
        binder.request(
            RuntimeProtocol.NOTIFICATION_SETTINGS,
            write = {
                it.writeInt(if (value == null) 0 else 1)
                if (value != null) NotificationSettingsWire.write(it, value)
            },
            read = NotificationSettingsWire::read,
        )

    fun quietHours(value: QuietHours?): QuietHours =
        binder.request(
            RuntimeProtocol.QUIET_HOURS,
            write = {
                it.writeInt(if (value == null) 0 else 1)
                if (value != null) QuietHoursWire.write(it, value)
            },
            read = QuietHoursWire::read,
        )

    fun receivePlan(
        value: io.github.beacon.app.domain.model.ReceivePlan? = null
    ): ReceivePlanReply =
        binder.request(
            RuntimeProtocol.RECEIVE_PLAN,
            write = {
                it.writeInt(if (value == null) 0 else 1)
                if (value != null) {
                    it.writeString(value.mode.key)
                    it.writeInt(if (value.realtimeWhileCharging) 1 else 0)
                    it.writeInt(if (value.realtimeOnWifi) 1 else 0)
                    ReceivePlanWire.writeExtras(it, value)
                }
            },
        ) {
            val result =
                ReceivePlanReply(
                    io.github.beacon.app.domain.model.ReceivePlan(
                        io.github.beacon.app.domain.model.ReceiveMode.fromKey(it.readString()),
                        it.readInt() == 1,
                        it.readInt() == 1,
                    ),
                    requireNotNull(it.readString()),
                    it.readInt() == 1,
                    it.readLong(),
                    PollingWire.decode(requireNotNull(it.readString())),
                    io.github.beacon.app.domain.model
                        .RealtimeDiagnostics(
                            io.github.beacon.app.domain.model.RealtimeFailure.valueOf(
                                requireNotNull(it.readString())
                            ),
                            it.readInt(),
                            it.readInt(),
                            it.readLong(),
                        )
                        .also { value -> value.validate() },
                    io.github.beacon.app.domain.model
                        .RecoverySnapshot(
                            io.github.beacon.app.domain.model.RecoveryStatus.valueOf(
                                requireNotNull(it.readString())
                            ),
                            it.readLong(),
                            it.readInt(),
                        )
                        .also { value -> value.validate() },
                )
            val plan=ReceivePlanWire.readExtras(it,result.plan)
            val diagnostics=if(it.dataAvail()>=16)result.diagnostics.copy(nextRetryAtMs=it.readLong(),
                disconnectedSinceMs=it.readLong()).also{value->value.validate()}else result.diagnostics
            result.copy(plan=plan,diagnostics=diagnostics)
        }

    fun bindDevice(request: BindingRequest): RuntimeControlReply {
        request.validate(BuildConfig.DEBUG)
        return binder.request(
            RuntimeProtocol.BIND_DEVICE,
            write = {
                it.writeString(request.endpoint)
                it.writeString(request.code)
                it.writeString(request.deviceName)
            },
            read = ::readReply,
        )
    }

    fun reconnectRealtime(): io.github.beacon.app.domain.policy.RealtimeRetryDecision =
        binder.request(RuntimeProtocol.RECONNECT_REALTIME) {
            requireNotNull(
                io.github.beacon.app.domain.policy.RealtimeRetryDecision.fromWireId(it.readInt())
            )
        }

    fun rotateCredentials(payload: String): RuntimeControlReply {
        io.github.beacon.app.data.remote.RotatedCredentialsDecoder.decode(payload)
        return binder.request(
            RuntimeProtocol.ROTATE_CREDENTIALS,
            write = { it.writeString(payload) },
            read = ::readReply,
        )
    }

    fun revokeDevice(): RuntimeControlReply =
        binder.request(RuntimeProtocol.REVOKE_DEVICE, read = ::readReply)

    fun clearConnection(): PollingState =
        binder.request(RuntimeProtocol.CLEAR_CONNECTION) {
            PollingWire.decode(requireNotNull(it.readString()))
        }

    fun pushKey(): String =
        binder.request(RuntimeProtocol.COPY_PUSH_KEY) {
            requireNotNull(it.readString()).also { key ->
                require(key.matches(Regex("[0-9a-f]{64}")))
            }
        }

    /**
     * Probes the saved server. [force] skips the runtime's cached verdict; the automatic foreground
     * check leaves it false so a live stream or a recent answer is reused instead of re-probing.
     */
    fun checkServer(force: Boolean = false): Pair<Boolean, String> =
        binder.request(
            RuntimeProtocol.CHECK_SERVER,
            write = { it.writeInt(if (force) 1 else 0) },
        ) {
            Pair(it.readInt() == 1, requireNotNull(it.readString()))
        }

    /**
     * Reads what the runtime already knows without touching the network: the cached probe verdict
     * and how long ago the realtime stream last proved itself.
     */
    fun status(): RuntimeStatusReply =
        binder.request(RuntimeProtocol.GET_STATUS) {
            RuntimeStatusReply(
                endpoint = requireNotNull(it.readString()),
                hasVerdict = it.readInt() == 1,
                reachable = it.readInt() == 1,
                message = requireNotNull(it.readString()),
                checkedAtMs = it.readLong(),
                realtimeRunning = it.readInt() == 1,
                heartbeatAgeMs = it.readLong(),
                heartbeatIntervalMs = it.readLong(),
            )
        }

    fun syncWidgetTopics(): String =
        binder.request(RuntimeProtocol.SYNC_WIDGET_TOPICS) { requireNotNull(it.readString()) }

    fun address(): String =
        binder.request(RuntimeProtocol.GET_CONNECTION) { requireNotNull(it.readString()) }

    fun state(): PollingState =
        binder.request(RuntimeProtocol.GET_CONTROL) {
            PollingWire.decode(requireNotNull(it.readString()))
        }

    fun transport(settings: TransportSettings?, notifyOnly: Boolean): RuntimeControlReply {
        settings?.validateForUpdate(BuildConfig.DEBUG)
        val operation =
            when {
                settings != null -> RuntimeProtocol.CONFIGURE
                notifyOnly -> RuntimeProtocol.NOTIFY
                else -> RuntimeProtocol.SYNC
            }
        return binder.request(
            operation,
            write = {
                if (settings != null) {
                    it.writeString(settings.endpoint)
                    it.writeString(settings.token)
                }
            },
            read = ::readReply,
        )
    }

    fun setPolling(config: PollingConfig): RuntimeControlReply =
        binder.request(
            RuntimeProtocol.SET_POLLING,
            write = {
                it.writeInt(if (config.enabled) 1 else 0)
                it.writeInt(config.intervalMinutes)
                it.writeInt(if (config.unmetered) 1 else 0)
                it.writeInt(if (config.charging) 1 else 0)
            },
            read = ::readReply,
        )

    private fun readReply(reply: android.os.Parcel) =
        RuntimeControlReply(
            requireNotNull(reply.readString()),
            PollingWire.decode(requireNotNull(reply.readString())),
            requireNotNull(SyncOutcome.fromWireId(reply.readInt())),
        )
}

/** Snapshot of the runtime's own view of the saved server, read without network IO. */
internal data class RuntimeStatusReply(
    val endpoint: String,
    val hasVerdict: Boolean,
    val reachable: Boolean,
    val message: String,
    val checkedAtMs: Long,
    val realtimeRunning: Boolean,
    val heartbeatAgeMs: Long,
    val heartbeatIntervalMs: Long,
)

internal data class ReceivePlanReply(
    val plan: io.github.beacon.app.domain.model.ReceivePlan,
    val status: String,
    val running: Boolean,
    val deliveryRevision: Long,
    val polling: PollingState,
    val diagnostics: io.github.beacon.app.domain.model.RealtimeDiagnostics,
    val recovery: io.github.beacon.app.domain.model.RecoverySnapshot,
)
