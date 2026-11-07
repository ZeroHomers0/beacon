package io.github.beacon.app.runtime

import android.os.Parcel
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.data.local.PollingWire
import io.github.beacon.app.data.local.TransportSecretStore
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.RuntimeEventKind
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.model.TransportSettings
import io.github.beacon.app.domain.receive.SyncReport
import io.github.beacon.app.ipc.RuntimeProtocol.AUTO_SYNC
import io.github.beacon.app.ipc.RuntimeProtocol.CONFIGURE
import io.github.beacon.app.ipc.RuntimeProtocol.GET_CONTROL
import io.github.beacon.app.ipc.RuntimeProtocol.NOTIFY
import io.github.beacon.app.ipc.RuntimeProtocol.SET_POLLING
import io.github.beacon.app.ipc.RuntimeProtocol.STOP_RUN
import io.github.beacon.app.ipc.RuntimeProtocol.SYNC

/** IPC payload handling; invoked only after the service validates caller UID and descriptor. */
internal fun RuntimeProbeService.handleSyncCommands(code: Int, data: Parcel, reply: Parcel?): Boolean {
    if (code in listOf(GET_CONTROL, SET_POLLING, AUTO_SYNC, STOP_RUN)) {
        check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            "接收设置加载超时"
        }
    }
    if (code == GET_CONTROL || code == STOP_RUN) {
        val runId =
            if (code == STOP_RUN)
                requireNotNull(data.readString()).also { require(it.length in 1..64) }
            else null
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        if (runId != null) activeRuns[runId]?.set(false)
        requireNotNull(reply).apply {
            writeNoException()
            writeString(PollingWire.encode(pollingState))
        }
        return true
    }
    if (code in listOf(CONFIGURE, SYNC, NOTIFY, SET_POLLING, AUTO_SYNC)) {
        val settings =
            if (code == CONFIGURE)
                TransportSettings(
                    requireNotNull(data.readString()),
                    requireNotNull(data.readString()),
                )
            else null
        settings?.validateForUpdate(BuildConfig.DEBUG)
        val config =
            if (code == SET_POLLING)
                PollingConfig(
                        data.readInt() == 1,
                        data.readInt(),
                        data.readInt() == 1,
                        data.readInt() == 1,
                    )
                    .also { it.validate() }
            else null
        val runId =
            if (code == AUTO_SYNC)
                requireNotNull(data.readString()).also { require(it.length in 1..64) }
            else null
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        if (settings != null || config != null) generation.incrementAndGet()
        val expectedGeneration = generation.get()
        val networkOperation = code == SYNC || code == AUTO_SYNC
        if (networkOperation && !syncing.compareAndSet(false, true)) {
            requireNotNull(reply).apply {
                writeNoException()
                writeString("已有同步正在执行，请稍后读取结果")
                writeString(PollingWire.encode(pollingState))
                writeInt(SyncOutcome.BUSY.wireId)
            }
            return true
        }
        val active =
            if (runId == null) null
            else
                java.util.concurrent.atomic.AtomicBoolean(true).also {
                    activeRuns[runId] = it
                }
        val result =
            try {
                worker
                    .submit<SyncReport> {
                        try {
                            when {
                                settings != null -> {
                                    val secret =
                                        TransportSecretStore(this@handleSyncCommands)
                                    secret.save(
                                        settings.resolved(BuildConfig.DEBUG) {
                                            secret.load().token
                                        }
                                    )
                                    saveState(
                                        pollingState.copy(
                                            blocked = "",
                                            config = pollingState.config.copy(enabled = true),
                                        )
                                    )
                                    realtime.reset()
                                    realtime.reconcile()
                                    recordEvent(RuntimeEventKind.CONNECTION_SAVED)
                                    SyncReport(SyncOutcome.OK, "连接配置已加密保存；请点击立即同步验证")
                                }
                                config != null -> {
                                    if (config.enabled)
                                        TransportSecretStore(this@handleSyncCommands)
                                            .load()
                                    saveState(
                                        pollingState.copy(
                                            config = config,
                                            blocked =
                                                if (config.enabled) ""
                                                else pollingState.blocked,
                                        )
                                    )
                                    realtime.reset()
                                    realtime.reconcile()
                                    SyncReport(
                                        SyncOutcome.OK,
                                        if (config.enabled) "接收设置已保存，正在应用调度"
                                        else "自动接收已暂停；保留历史和待回传记录，可主动手动同步",
                                    )
                                }
                                code == NOTIFY ->
                                    SyncReport(
                                        SyncOutcome.OK,
                                        flushNotifications(
                                            this@handleSyncCommands,
                                            store,
                                        ),
                                    )
                                else ->
                                    sync(code == AUTO_SYNC, expectedGeneration, active)
                            }
                        } finally {
                            if (networkOperation) syncing.set(false)
                            if (runId != null) activeRuns.remove(runId)
                        }
                    }
                    .get(45, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw IllegalStateException("Transport operation failed", e)
            }
        requireNotNull(reply).apply {
            writeNoException()
            writeString(result.text)
            writeString(PollingWire.encode(pollingState))
            writeInt(result.outcome.wireId)
        }
        return true
    }

    return false
}
