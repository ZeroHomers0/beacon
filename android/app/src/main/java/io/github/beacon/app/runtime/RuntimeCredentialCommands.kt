package io.github.beacon.app.runtime

import android.os.Parcel
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.data.local.PollingWire
import io.github.beacon.app.data.local.TransportSecretStore
import io.github.beacon.app.data.remote.BindingRejected
import io.github.beacon.app.data.remote.BindingResponseInvalid
import io.github.beacon.app.data.remote.HttpDeviceBinding
import io.github.beacon.app.data.remote.HttpMessageSync
import io.github.beacon.app.domain.model.BindingRequest
import io.github.beacon.app.domain.model.RuntimeEventKind
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.policy.SyncFailure
import io.github.beacon.app.domain.receive.SyncReport
import io.github.beacon.app.ipc.RuntimeProtocol.GET_CONNECTION

/** IPC payload handling; invoked only after the service validates caller UID and descriptor. */
internal fun RuntimeProbeService.handleCredentialCommands(code: Int, data: Parcel, reply: Parcel?): Boolean {
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.COPY_PUSH_KEY) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val address =
            worker
                .submit<String> {
                    TransportSecretStore(this@handleCredentialCommands).pushKey()
                }
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeString(address)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.REVOKE_DEVICE) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        check(controlLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val deadline = System.nanoTime() + 30_000_000_000L
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val task =
            worker.submit<SyncReport> {
                var confirmed = false
                try {
                    check(active.get() && System.nanoTime() < deadline)
                    val secrets = TransportSecretStore(this@handleCredentialCommands)
                    val device = secrets.loadBound()
                    io.github.beacon.app.data.remote.HttpDeviceRevocation().revoke(
                        device,
                        deadline,
                    ) {
                        active.get()
                    }
                    confirmed = true
                    // Complete local cleanup after confirmation even if the caller
                    // leaves.
                    generation.incrementAndGet()
                    activeRuns.values.forEach { it.set(false) }
                    saveState(
                        pollingState.copy(
                            config = pollingState.config.copy(enabled = false),
                            blocked = "",
                        )
                    )
                    realtime.reset()
                    realtime.reconcile()
                    store.retireRevokedReceipts(
                        io.github.beacon.app.data.remote.MessageIdentity.namespace(
                            device.serverId,
                            device.deviceId,
                        )
                    )
                    recordEvent(RuntimeEventKind.DEVICE_REVOKED)
                    // Clear last: a partial cleanup can retry the idempotent remote
                    // request.
                    val stateSource=store.stateCache.preference("source-for:"+device.settings.endpoint)
                    if(stateSource.isNotEmpty())store.stateCache.forget(stateSource)
                    io.github.beacon.app.widget.BeaconStateWidget.refresh(this@handleCredentialCommands)
                    io.github.beacon.app.widget.StateNotifications.refresh(this@handleCredentialCommands)
                    secrets.clear()
                    SyncReport(SyncOutcome.OK, "当前设备已撤销并清除本机配置；历史消息保留，旧推送地址已失效")
                } catch (error: Exception) {
                    SyncReport(
                        SyncOutcome.CONFIG,
                        if (confirmed) "服务端已撤销，本机清理未完成；请重试完整解绑"
                        else
                            when (error) {
                                is io.github.beacon.app.data.remote.DeviceRevocationRejected ->
                                    if (error.status == 404)
                                        "服务器暂不支持完整解绑，请先升级服务器；本机配置保留"
                                    else "服务端未确认撤销；本机配置保留，请核对设备凭证后重试"
                                is IllegalArgumentException ->
                                    "当前配置缺少绑定设备身份；请在服务端撤销，或仅清除本机配置"
                                else -> "撤销结果未确认；本机配置保留，请检查网络后重试"
                            },
                    )
                }
            }
        val result =
            try {
                task.get(35, java.util.concurrent.TimeUnit.SECONDS)
            } catch (error: Exception) {
                active.set(false)
                task.cancel(false)
                throw error
            }
        requireNotNull(reply).apply {
            writeNoException()
            writeString(result.text)
            writeString(PollingWire.encode(pollingState))
            writeInt(result.outcome.wireId)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.ROTATE_CREDENTIALS) {
        val deadline = System.nanoTime() + 30_000_000_000L
        val payload = requireNotNull(data.readString())
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val credentials =
            io.github.beacon.app.data.remote.RotatedCredentialsDecoder.decode(payload)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        fun allowed() = active.get() && System.nanoTime() < deadline
        val task =
            worker.submit<SyncReport> {
                var saved = false
                try {
                    check(allowed())
                    TransportSecretStore(this@handleCredentialCommands).rotate(
                        credentials
                    ) { device ->
                        HttpMessageSync(device.settings, store, ::allowed)
                            .verifyDevice(device.serverId, device.deviceId)
                        check(allowed())
                    }
                    saved = true
                    generation.incrementAndGet()
                    realtime.reset()
                    saveState(pollingState.copy(blocked = "", config = pollingState.config.copy(enabled = true)))
                    recordEvent(RuntimeEventKind.CREDENTIALS_ROTATED)
                    realtime.reconcile()
                    SyncReport(SyncOutcome.OK, "新凭证已验证并加密保存；历史与接收开关保留，请更新发送脚本中的推送地址")
                } catch (error: Exception) {
                    if (saved) SyncReport(SyncOutcome.OK, "新凭证已保存，接收状态更新未完成；请刷新状态或重开应用")
                    else
                        SyncReport(
                            SyncOutcome.CONFIG,
                            when (error) {
                                is IllegalArgumentException ->
                                    "更新未完成：结果不属于当前绑定设备、格式无效或已使用；原配置保留"
                                else -> "新凭证验证或保存未完成；原配置保留，请核对网络、证书和最新轮换结果"
                            },
                        )
                }
            }
        val result =
            try {
                task.get(35, java.util.concurrent.TimeUnit.SECONDS)
            } catch (error: Exception) {
                active.set(false)
                task.cancel(false)
                throw error
            }
        requireNotNull(reply).apply {
            writeNoException()
            writeString(result.text)
            writeString(PollingWire.encode(pollingState))
            writeInt(result.outcome.wireId)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.BIND_DEVICE) {
        val deadline = System.nanoTime() + 30_000_000_000L
        val request =
            BindingRequest(
                requireNotNull(data.readString()),
                requireNotNull(data.readString()),
                requireNotNull(data.readString()),
            )
        request.validate(BuildConfig.DEBUG)
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val result =
            worker
                .submit<SyncReport> {
                    try {
                        val device = HttpDeviceBinding().bind(request, deadline)
                        TransportSecretStore(this@handleCredentialCommands).saveBound(device)
                        recordEvent(RuntimeEventKind.CONNECTION_SAVED)
                        generation.incrementAndGet()
                        saveState(
                            pollingState.copy(blocked = "", config = pollingState.config.copy(enabled = true))
                        )
                        realtime.reset()
                        realtime.reconcile()
                        SyncReport(SyncOutcome.OK, "服务器已绑定，凭证已加密保存；可复制推送密钥并立即同步验证")
                    } catch (error: Exception) {
                        val reason =
                            when (error) {
                                is BindingRejected ->
                                    when (error.status) {
                                        400 -> "绑定码无效、已使用或已过期，请重新生成"
                                        404 -> "服务器尚不支持绑定码，请先升级服务器"
                                        429 -> "绑定请求过多，请稍后重试"
                                        in 300..399 -> "服务器发生重定向，请填写最终 HTTPS 地址"
                                        else -> "服务器未完成绑定，请稍后重试"
                                    }
                                is BindingResponseInvalid -> "服务器绑定响应不符合协议，请核对服务器版本"
                                else -> "绑定未完成；若绑定码已消耗，请在服务器生成新码"
                            }
                        SyncReport(SyncOutcome.CONFIG, reason)
                    }
                }
                .get(35, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeString(result.text)
            writeString(PollingWire.encode(pollingState))
            writeInt(result.outcome.wireId)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.CHECK_SERVER) {
        val force = data.readInt().also { require(it in 0..1) } == 1
        require(data.dataAvail() == 0)
        // A live stream or a verdict younger than the freshness window answers the
        // question already; only a forced or stale read goes to the network.
        val cached =
            if (force) null
            else
                statusStore.load()?.let { verdict ->
                    val endpoint =
                        worker
                            .submit<String> {
                                val secret = TransportSecretStore(this@handleCredentialCommands)
                                if (secret.isConfigured()) secret.load().endpoint else ""
                            }
                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (verdict.endpoint == endpoint) verdict else null
                }
        val result =
            if (cached != null) Pair(cached.reachable, cached.message)
            else
            serverProbe
                .submit<Pair<Boolean, String>> {
                    try {
                        // Snapshot credentials on their owner, then release it before
                        // network IO so local queries and mutations can proceed.
                        val sync =
                            worker
                                .submit<HttpMessageSync> {
                                    HttpMessageSync(
                                        TransportSecretStore(this@handleCredentialCommands)
                                            .load(),
                                        store,
                                    )
                                }
                                .get(5, java.util.concurrent.TimeUnit.SECONDS)
                        Pair(true, sync.checkServer())
                    } catch (error: Exception) {
                        Pair(
                            false,
                            SyncFailure.describe(error, SyncFailure.outcome(error))
                                .substringBefore("；已保存"),
                        )
                    }
                }
                .get(35, java.util.concurrent.TimeUnit.SECONDS)
        if (cached == null) {
            runCatching {
                val success = result.first
                if (success || result.second.isNotBlank()) {
                    val endpoint =
                        worker
                            .submit<String> {
                                val secret = TransportSecretStore(this@handleCredentialCommands)
                                if (secret.isConfigured()) secret.load().endpoint else ""
                            }
                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (endpoint.isNotBlank()) {
                        statusStore.save(
                            endpoint,
                            success,
                            result.second,
                            System.currentTimeMillis(),
                        )
                    }
                }
            }
        }
        requireNotNull(reply).apply {
            writeNoException()
            writeInt(if (result.first) 1 else 0)
            writeString(result.second)
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.GET_STATUS) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val snapshot =
            worker
                .submit<List<Long>> {
                    val verdict = statusStore.load()
                    listOf(
                        if (verdict == null) 0L else 1L,
                        if (verdict?.reachable == true) 1L else 0L,
                        verdict?.checkedAtMs ?: 0L,
                        if (realtime.running) 1L else 0L,
                        if (realtime.lastHeartbeatAtMs <= 0L) -1L
                        else
                            android.os.SystemClock.elapsedRealtime() -
                                realtime.lastHeartbeatAtMs,
                        realtime.plan.heartbeatSeconds * 1000L,
                    )
                }
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
        val endpoint =
            worker
                .submit<String> {
                    val secret = TransportSecretStore(this@handleCredentialCommands)
                    if (secret.isConfigured()) secret.load().endpoint else ""
                }
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
        val verdict = statusStore.load()
        requireNotNull(reply).apply {
            writeNoException()
            writeString(endpoint)
            writeInt(snapshot[0].toInt())
            writeInt(snapshot[1].toInt())
            writeString(verdict?.message ?: "")
            writeLong(snapshot[2])
            writeInt(snapshot[3].toInt())
            writeLong(snapshot[4])
            writeLong(snapshot[5])
        }
        return true
    }
    if (code == io.github.beacon.app.ipc.RuntimeProtocol.SYNC_WIDGET_TOPICS) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val expected = generation.get()
        val request = worker.submit<String> {
            val secrets = TransportSecretStore(this@handleCredentialCommands)
            if (!secrets.isConfigured()) return@submit "请先在 App 中绑定这台设备，再选择展示内容。"
            try {
                val sync = HttpMessageSync(secrets.load(), store, { generation.get() == expected })
                if (sync.syncTopicCatalog()) "已同步当前绑定设备的主题；未上报数据的主题也可选择。"
                else "当前服务器不支持预创建主题目录，请升级服务器；已有内容仍可使用。"
            } catch (error: Exception) {
                "主题同步未完成。" + SyncFailure.describe(error, SyncFailure.outcome(error))
            }
        }
        val message = try { request.get(35, java.util.concurrent.TimeUnit.SECONDS) }
            catch (_: java.util.concurrent.TimeoutException) {
                request.cancel(true)
                "主题同步超时，保留本地选项，请重试。"
            }
        requireNotNull(reply).apply { writeNoException(); writeString(message) }
        return true
    }
    if (code == GET_CONNECTION) {
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val address =
            worker
                .submit<String> {
                    val secret = TransportSecretStore(this@handleCredentialCommands)
                    if (secret.isConfigured()) secret.load().endpoint else ""
                }
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
        requireNotNull(reply).apply {
            writeNoException()
            writeString(address)
        }
        return true
    }

    return false
}
