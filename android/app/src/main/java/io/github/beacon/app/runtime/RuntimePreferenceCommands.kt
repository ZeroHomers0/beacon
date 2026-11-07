package io.github.beacon.app.runtime

import android.os.Parcel
import io.github.beacon.app.data.local.NotificationSettingsStore
import io.github.beacon.app.data.local.PollingWire
import io.github.beacon.app.data.local.QuietHoursStore
import io.github.beacon.app.data.local.RecoveryStatusStore
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.ipc.NotificationSettingsWire
import io.github.beacon.app.ipc.QuietHoursWire

/** IPC payload handling; invoked only after the service validates caller UID and descriptor. */
internal fun RuntimeProbeService.handlePreferenceCommands(code: Int, data: Parcel, reply: Parcel?): Boolean {
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.REMIND_MESSAGE) {
        val key =
            MessageKey(
                    requireNotNull(data.readString()),
                    requireNotNull(data.readString()),
                )
                .also { it.validate() }
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val request =
            worker.submit<String> {
                remindMessage(this@handlePreferenceCommands, store, key)
            }
        val result =
            try {
                request.get(5, java.util.concurrent.TimeUnit.SECONDS)
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
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.RECEIVE_PLAN) {
        val write = data.readInt().also { require(it in 0..1) } == 1
        val plan =
            if (write) {
                val mode = requireNotNull(data.readString())
                require(
                    io.github.beacon.app.domain.model.ReceiveMode.entries.any {
                        it.key == mode
                    }
                )
                val charging = data.readInt().also { require(it in 0..1) } == 1
                val wifi = data.readInt().also { require(it in 0..1) } == 1
                io.github.beacon.app.ipc.ReceivePlanWire.readExtras(
                    data,
                    io.github.beacon.app.domain.model.ReceivePlan(
                        io.github.beacon.app.domain.model.ReceiveMode.fromKey(mode),
                        charging,
                        wifi,
                    ),
                )
            } else null
        require(data.dataAvail() == 0)
        if (write) generation.incrementAndGet()
        val snapshot =
            worker
                .submit<List<String>> {
                    if (plan != null) realtime.save(plan)
                    val diagnostics = realtime.diagnostics
                    val recovery =
                        io.github.beacon.app.data.local
                            .RecoveryStatusStore(this@handlePreferenceCommands)
                            .load()
                    listOf(
                        realtime.plan.mode.key,
                        if (realtime.plan.realtimeWhileCharging) "1" else "0",
                        if (realtime.plan.realtimeOnWifi) "1" else "0",
                        realtime.status,
                        if (realtime.running) "1" else "0",
                        (realtime.deliveryRevision +
                                io.github.beacon.app.runtime.notification
                                    .NotificationReadReceiver
                                    .revision
                                    .get())
                            .toString(),
                        PollingWire.encode(pollingState),
                        diagnostics.failure.name,
                        diagnostics.attempts.toString(),
                        diagnostics.retries.toString(),
                        diagnostics.connectedAtMs.toString(),
                        recovery.status.name,
                        recovery.atMs.toString(),
                        recovery.attempts.toString(),
                        if (realtime.plan.scheduleEnabled) "1" else "0",
                        realtime.plan.scheduleStart.toString(),
                        realtime.plan.scheduleEnd.toString(),
                        if (realtime.plan.scheduleRealtime) "1" else "0",
                        if (realtime.plan.lowBatteryEnabled) "1" else "0",
                        realtime.plan.lowBatteryPercent.toString(),
                        realtime.plan.heartbeatSeconds.toString(),
                        if (realtime.plan.adaptiveHeartbeat) "1" else "0",
                        if (realtime.plan.batchOrdinaryMessages) "1" else "0",
                        diagnostics.nextRetryAtMs.toString(),
                        diagnostics.disconnectedSinceMs.toString(),
                    )
                }
                .get(45, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeString(snapshot[0])
            writeInt(snapshot[1].toInt())
            writeInt(snapshot[2].toInt())
            writeString(snapshot[3])
            writeInt(snapshot[4].toInt())
            writeLong(snapshot[5].toLong())
            writeString(snapshot[6])
            writeString(snapshot[7])
            writeInt(snapshot[8].toInt())
            writeInt(snapshot[9].toInt())
            writeLong(snapshot[10].toLong())
            writeString(snapshot[11])
            writeLong(snapshot[12].toLong())
            writeInt(snapshot[13].toInt())
            for (index in 14..22) writeInt(snapshot[index].toInt())
            writeLong(snapshot[23].toLong())
            writeLong(snapshot[24].toLong())
        }
        return true
    }
    if(code==io.github.beacon.app.ipc.RuntimeProtocol.TEMPORARY_MUTE) {
        val scope=io.github.beacon.app.domain.model.MuteScope(
            io.github.beacon.app.domain.model.MuteKind.valueOf(requireNotNull(data.readString())),
            requireNotNull(data.readString()),requireNotNull(data.readString())).also{it.validate()}
        val writing=data.readInt().also{require(it in 0..1)}==1
        val until=if(writing)data.readLong().also{require(it>=0)}else null
        require(data.dataAvail()==0)
        val value=worker.submit<io.github.beacon.app.domain.model.MuteSnapshot>{
            val preferences=io.github.beacon.app.data.local.TemporaryMuteStore(this@handlePreferenceCommands)
            if(until!=null){
                preferences.save(scope,until)
                if(until>System.currentTimeMillis())flushNotifications(this@handlePreferenceCommands,store)
                io.github.beacon.app.widget.StateRefreshReceiver.request(this@handlePreferenceCommands)
            }
            preferences.snapshot(scope)
        }.get(45,java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply{writeNoException();writeLong(value.localUntilMs);writeLong(value.globalUntilMs)}
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.NOTIFICATION_SETTINGS) {
        val write = data.readInt().also { require(it in 0..1) } == 1
        val value = if (write) NotificationSettingsWire.read(data) else null
        require(data.dataAvail() == 0)
        val saved =
            worker
                .submit<NotificationSettings> {
                    val preferences =
                        NotificationSettingsStore(this@handlePreferenceCommands)
                    if (value != null) preferences.save(value)
                    preferences.load()
                }
                .get(45, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            NotificationSettingsWire.write(this, saved)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.QUIET_HOURS) {
        val write = data.readInt().also { require(it in 0..1) } == 1
        val value = if (write) QuietHoursWire.read(data) else null
        require(data.dataAvail() == 0)
        val saved =
            worker
                .submit<io.github.beacon.app.domain.model.QuietHours> {
                    val preferences = QuietHoursStore(this@handlePreferenceCommands)
                    if (value != null) preferences.save(value)
                    preferences.load()
                }
                .get(45, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            QuietHoursWire.write(this, saved)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.CLEANUP_SETTINGS) {
        val write = data.readInt().also { require(it in 0..1) } == 1
        val settings =
            if (write)
                io.github.beacon.app.domain.model.CleanupSettings(
                    io.github.beacon.app.domain.model
                        .CleanupPolicy(
                            data.readInt(),
                            data.readInt(),
                            data.readInt().also { require(it in 0..1) } == 1,
                        )
                        .also { it.validate() },
                    data.readInt().also { require(it in 0..1) } == 1,
                )
            else null
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val saved =
            worker
                .submit<io.github.beacon.app.domain.model.CleanupSettings> {
                    val preferences =
                        io.github.beacon.app.data.local.CleanupPreferenceStore(
                            this@handlePreferenceCommands
                        )
                    if (settings != null) preferences.save(settings)
                    preferences.settings()
                }
                .get(45, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeInt(saved.policy.days)
            writeInt(saved.policy.maxMessages)
            writeInt(if (saved.policy.keepUnread) 1 else 0)
            writeInt(if (saved.automatic) 1 else 0)
            writeString(saved.lastResult)
        }
        return true
    }

    return false
}
