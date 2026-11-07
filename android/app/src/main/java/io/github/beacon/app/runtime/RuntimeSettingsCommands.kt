package io.github.beacon.app.runtime

import android.os.Parcel
import io.github.beacon.app.data.local.TransportSecretStore
import io.github.beacon.app.domain.model.RuntimeEvent
import io.github.beacon.app.runtime.notification.MessageNotifications

/** IPC payload handling; invoked only after the service validates caller UID and descriptor. */
internal fun RuntimeProbeService.handleSettingsCommands(code: Int, data: Parcel, reply: Parcel?): Boolean {
    if (
        code == io.github.beacon.app.ipc.RuntimeProtocol.RECONCILE_POLLING ||
            code == io.github.beacon.app.ipc.RuntimeProtocol.SCHEDULE_TEST
    ) {
        require(data.dataAvail() == 0)
        check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val task = scheduler.submit {
            if (code == io.github.beacon.app.ipc.RuntimeProtocol.SCHEDULE_TEST)
                PollingScheduler.testOnce(this@handleSettingsCommands, pollingState)
            else PollingScheduler.apply(this@handleSettingsCommands, pollingState)
        }
        try {
            task.get(25, java.util.concurrent.TimeUnit.SECONDS)
        } catch (error: java.util.concurrent.TimeoutException) {
            task.cancel(false)
            throw error
        }
        requireNotNull(reply).writeNoException()
        return true
    }
    if (
        code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_ALL_MESSAGES ||
            code == io.github.beacon.app.ipc.RuntimeProtocol.PENDING_REMINDERS
    ) {
        require(data.dataAvail() == 0)
        val clearing =
            code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_ALL_MESSAGES
        val result =
            worker
                .submit<Long> {
                    if (clearing) {
                        val count = store.clearAllMessages()
                        val manager =
                            getSystemService(
                                android.app.NotificationManager::class.java
                            )
                        manager.activeNotifications
                            .filter { it.id == MessageNotifications.ID }
                            .forEach { manager.cancel(it.tag, it.id) }
                        count
                    } else store.pendingReminderCount()
                }
                .get(45, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeLong(result)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.BARK_ENCRYPTION_STATUS) {
        require(data.dataAvail() == 0)
        val configured = worker.submit<Boolean> {
            val secrets = TransportSecretStore(this@handleSettingsCommands)
            secrets.isConfigured() && secrets.barkSecret() != null
        }.get(10, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply { writeNoException(); writeInt(if (configured) 1 else 0) }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.BARK_ENCRYPTION) {
        require(data.dataAvail() <= 1024)
        val key = requireNotNull(data.readString()).also { require(it.length <= 64) }
        val iv = requireNotNull(data.readString()).also { require(it.length <= 64) }
        require(data.dataAvail() == 0)
        val result =
            worker
                .submit<String> {
                    val secrets = TransportSecretStore(this@handleSettingsCommands)
                    if (key.isEmpty() && iv.isEmpty()) {
                        secrets.clearBarkSecret()
                        "Bark 解密配置已移除，已解密的历史消息保留"
                    } else {
                        secrets.saveBarkSecret(key, iv)
                        val count = store.decryptHistory(key to iv)
                        "Bark 解密配置已保存，解密 $count 条历史消息"
                    }
                }
                .get(30, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeString(result)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.TEST_DELIVERY) {
        require(data.dataAvail() == 0)
        val result =
            worker
                .submit<String> {
                    val device =
                        TransportSecretStore(this@handleSettingsCommands).loadBound()
                    val id =
                        io.github.beacon.app.data.remote.HttpDeliveryTest.send(device)
                    sync(false, generation.get(), null)
                    val namespace =
                        io.github.beacon.app.data.remote.MessageIdentity.namespace(
                            device.serverId,
                            device.deviceId,
                        )
                    val message = store.detail(namespace, id)
                    when {
                        message == null -> "服务器已接收测试推送，尚未在本机确认保存；请稍后同步"
                        message.reminderPending -> "测试消息已保存，系统提醒仍待提交；请检查通知权限"
                        else -> "测试消息已保存，提醒流程已处理；请确认系统通知中是否出现测试消息"
                    }
                }
                .get(60, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeString(result)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.HISTORY_ARCHIVE) {
        require(data.dataAvail() <= 20000)
        val uriText =
            requireNotNull(data.readString()).also { require(it.length <= 4096) }
        val password =
            requireNotNull(data.readString())
                .also { require(it.length <= 256) }
                .toCharArray()
        val importing = data.readInt().also { require(it in 0..1) } == 1
        val encrypted = data.readInt().also { require(it in 0..1) } == 1
        require(data.dataAvail() == 0 && (!encrypted || password.size in 8..256))
        val uri = android.net.Uri.parse(uriText)
        require(uri.scheme == "content")
        val task =
            worker.submit<String> {
                try {
                    val crypto = io.github.beacon.app.data.local.ArchiveCrypto
                    if (importing) {
                        val raw =
                            requireNotNull(contentResolver.openInputStream(uri)).use {
                                crypto.readBounded(it)
                            }
                        val payload =
                            if (encrypted) crypto.decrypt(raw, password) else raw
                        try {
                            "已导入 ${store.importHistory(payload)} 条新历史消息；已有消息保留，不重复通知"
                        } finally {
                            payload.fill(0)
                            raw.fill(0)
                        }
                    } else {
                        val payload = store.exportHistory()
                        val raw =
                            if (encrypted) crypto.encrypt(payload, password)
                            else payload
                        try {
                            requireNotNull(contentResolver.openOutputStream(uri, "wt"))
                                .use { it.write(raw) }
                            "消息历史已导出"
                        } finally {
                            raw.fill(0)
                            payload.fill(0)
                        }
                    }
                } finally {
                    password.fill('\u0000')
                }
            }
        val result = task.get(120, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeString(result)
        }
        return true
    }
    if (
        code == io.github.beacon.app.ipc.RuntimeProtocol.EXPORT_SETTINGS ||
            code == io.github.beacon.app.ipc.RuntimeProtocol.IMPORT_SETTINGS
    ) {
        val importing = code == io.github.beacon.app.ipc.RuntimeProtocol.IMPORT_SETTINGS
        require(data.dataAvail() <= 17000)
        val payload = if (importing) requireNotNull(data.readString()) else null
        require(data.dataAvail() == 0)
        check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val request =
            worker.submit<String> {
                val backup =
                    io.github.beacon.app.data.local.SettingsBackup(
                        this@handleSettingsCommands
                    )
                if (payload != null) {
                    pollingState = backup.restore(payload, pollingState)
                    reconcileSchedule()

                    realtime.reloadPreferences()
                }
                backup.export()
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
    if (
        code == io.github.beacon.app.ipc.RuntimeProtocol.GET_EVENTS ||
            code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_EVENTS
    ) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val request =
            worker.submit<List<RuntimeEvent>> {
                if (code == io.github.beacon.app.ipc.RuntimeProtocol.CLEAR_EVENTS) {
                    events.clear()
                    emptyList()
                } else events.read()
            }
        val history =
            try {
                request.get(10, java.util.concurrent.TimeUnit.SECONDS)
            } catch (error: java.util.concurrent.TimeoutException) {
                request.cancel(false)
                throw error
            }
        requireNotNull(reply).apply {
            writeNoException()
            if (code == io.github.beacon.app.ipc.RuntimeProtocol.GET_EVENTS) {
                writeInt(history.size)
                history.forEach {
                    writeLong(it.atMs)
                    writeInt(it.kind.wireId)
                    writeInt(it.outcome?.wireId ?: -1)
                    writeString(it.failure.name)
                }
            }
        }
        return true
    }

    return false
}
