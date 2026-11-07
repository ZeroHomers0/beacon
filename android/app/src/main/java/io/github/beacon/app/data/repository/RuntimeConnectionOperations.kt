package io.github.beacon.app.data.repository

import io.github.beacon.app.BuildConfig
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.OperationFailure
import io.github.beacon.app.domain.policy.RealtimeRetryDecision
import io.github.beacon.app.domain.policy.ServerProbePolicy
import io.github.beacon.app.ipc.RuntimeControlClient
import io.github.beacon.app.ipc.RuntimeStatusReply
import io.github.beacon.app.domain.policy.ServerStatusPolicy
import io.github.beacon.app.ipc.RuntimeEventClient
import io.github.beacon.app.ipc.request
import kotlinx.coroutines.flow.update

/** connection use cases; repository remains the lifecycle/state owner. */
internal fun RuntimeRepository.performBarkEncryptionConfigured(onComplete: (Boolean?) -> Unit) {
    if (!ready()) { onComplete(null); return }
    connection.execute({ RuntimeControlClient(it).barkEncryptionConfigured() },
        { onComplete(it) }, { onComplete(null) })
}

internal fun RuntimeRepository.performBarkEncryption(key: String, iv: String, onFailure: (String) -> Unit, onComplete: (String) -> Unit) {
    if (!ready() || state.value.transportBusy) {
        onFailure("请等待接收服务就绪")
        return
    }
    if (!(key.isEmpty() && iv.isEmpty()) && !io.github.beacon.app.domain.policy.BarkEncryptionValidation.valid(key, iv)) {
        onFailure("密钥需为16、24或32字节，默认IV需为16字节")
        return
    }
    mutableState.update { it.copy(transportBusy = true) }
    connection.execute(
        { RuntimeControlClient(it).barkEncryption(key, iv) },
        {
            mutableState.update { state -> state.copy(transportBusy = false) }
            readMessages()
            onComplete(it)
        },
        {
            mutableState.update { state -> state.copy(transportBusy = false) }
            onFailure("保存未确认，请检查配置后重试")
        },
    )
}

internal fun RuntimeRepository.performTestDelivery(onFailure: (String) -> Unit, onComplete: (String) -> Unit) {
    if (!ready() || state.value.messageBusy || state.value.transportBusy) {
        onFailure("请等待当前操作完成")
        return
    }
    mutableState.update { it.copy(transportBusy = true) }
    connection.execute(
        { RuntimeControlClient(it).testDelivery() },
        {
            mutableState.update { state -> state.copy(transportBusy = false) }
            readMessages()
            onComplete(it)
        },
        {
            mutableState.update { state -> state.copy(transportBusy = false) }
            onFailure("测试未确认，请先同步并查看消息列表；发送请求不会自动重试")
        },
    )
}

internal fun RuntimeRepository.performRevokeDevice(onRevoked: () -> Unit) {
    if (state.value.messageBusy || !ready()) return
    updateConnection(
        "正在撤销当前设备",
        "",
        { RuntimeControlClient(it).revokeDevice() },
        {
            mutableState.update {
                it.copy(
                    realtimeRunning = false,
                    realtimeStatus = "自动接收已暂停",
                    serverCheckResult = "尚未配置服务器",
                )
            }
            receivePlan()
            refreshPolling()
            onRevoked()
        },
        "解绑结果未确认；请刷新接收状态与服务器配置后再操作，历史消息保留",
    )
}

internal fun RuntimeRepository.performClearConnection(onCleared: () -> Unit) {
    if (state.value.messageBusy || !ready()) return
    invalidateServerCheck()
    mutableState.update { it.copy(transportBusy = true) }
    connection.execute(
        { RuntimeControlClient(it).clearConnection() },
        { polling ->
            mutableState.update {
                it.copy(
                    transportBusy = false,
                    configuredEndpoint = "",
                    connectionSummaryLoaded = true,
                    pollingState = polling,
                    serverChecking = false,
                    serverReachable = null,
                    serverCheckResult = "尚未配置服务器",
                    serverCheckedAtMs = 0,
                    realtimeRunning = false,
                    realtimeStatus = "自动接收已暂停",
                    lastManualOutcome = null,
                )
            }
            receivePlan()
            refreshPolling()
            onCleared()
            setStatus(OperationStatus.result("本机服务器配置已清除；历史消息保留，服务端设备未撤销"))
        },
        { error ->
            mutableState.update { it.copy(transportBusy = false) }
            refreshPolling()
            refreshConnectionSummary()
            setStatus(
                OperationStatus.result(
                    "配置清除未完成（${OperationFailure.describe(error,crossProcess=true)}），请检查接收状态后重试"
                )
            )
        },
    )
}

internal fun RuntimeRepository.performEventLog(onFailure: () -> Unit, onComplete: (List<RuntimeEvent>) -> Unit) {
    if (!ready()) {
        onFailure()
        return
    }
    connection.execute(
        { RuntimeEventClient(it).read() },
        onComplete,
        { onFailure() },
    )
}

internal fun RuntimeRepository.performClearEventLog(onFailure: () -> Unit, onComplete: () -> Unit) {
    if (!ready()) {
        onFailure()
        return
    }
    connection.execute(
        { RuntimeEventClient(it).clear() },
        { onComplete() },
        { onFailure() },
    )
}

internal fun RuntimeRepository.performReconnectRealtime() {
    if (state.value.messageBusy || !ready()) return
    mutableState.update {
        it.copy(transportBusy = true, status = OperationStatus.pending("正在请求即时连接重试"))
    }
    connection.execute(
        { RuntimeControlClient(it).reconnectRealtime() },
        { decision ->
            mutableState.update {
                it.copy(
                    transportBusy = false,
                    status =
                        OperationStatus.result(
                            when (decision) {
                                RealtimeRetryDecision.ALLOWED ->
                                    "已请求重新连接；仍按接收模式和网络条件运行，请刷新状态查看结果"
                                RealtimeRetryDecision.NO_CONFIGURATION -> "请先保存服务器配置"
                                RealtimeRetryDecision.POWER_SAVING -> "省电模式无需即时连接；请先切换接收模式"
                                RealtimeRetryDecision.PAUSED -> "自动接收已暂停，请先开启接收"
                                RealtimeRetryDecision.AUTH_BLOCKED -> "接收凭证失效，请重新绑定服务器"
                                RealtimeRetryDecision.SETTINGS_BLOCKED -> "接收设置被阻止，请先修复配置"
                            }
                        ),
                )
            }
            refreshPolling()
            receivePlan()
        },
        { error ->
            failTransport(
                "重连请求未完成（${OperationFailure.describe(error, crossProcess = true)}）；请刷新状态查看结果"
            )
        },
    )
}

internal fun RuntimeRepository.startServerCheck(manual: Boolean) {
    // Take over the display of an in-flight automatic probe that the user did not ask for.
    if (manual && serverProbeInFlight && !state.value.serverChecking) {
        serverProbeInFlight = false
        serverCheckGeneration++
    }
    if (
        !state.value.runtimeConnected ||
            !connection.connected ||
            !state.value.connectionSummaryLoaded
    ) {
        // Remember the owed check until the connection summary has been read.
        pendingServerCheck = manual || pendingServerCheck == true
        return
    }
    if (state.value.configuredEndpoint.isBlank()) {
        mutableState.update {
            it.copy(
                serverChecking = false,
                serverReachable = null,
                serverCheckResult = "尚未配置服务器",
                serverCheckedAtMs = 0L,
            )
        }
        return
    }
    if (!manual) {
        // An automatic check first asks the runtime what it already knows: a live stream, or a
        // verdict inside the freshness window, answers the question without a network round
        // trip. Only a silent, stale runtime triggers the probe.
        connection.execute(
            { RuntimeControlClient(it).status() },
            { reply ->
                applyRuntimeStatus(reply)
                val live =
                    ServerStatusPolicy.websocketLive(
                        running = reply.realtimeRunning,
                        heartbeatAgeMs = reply.heartbeatAgeMs,
                        heartbeatIntervalMs = reply.heartbeatIntervalMs,
                    )
                val ageMs =
                    if (reply.checkedAtMs <= 0L) -1L
                    else System.currentTimeMillis() - reply.checkedAtMs
                if (ServerStatusPolicy.needsProbe(live, ageMs)) runServerProbe(manual = false)
                else pendingServerCheck = null
            },
            { runServerProbe(manual = false) },
        )
        return
    }
    runServerProbe(manual = true)
}

internal fun RuntimeRepository.applyRuntimeStatus(reply: RuntimeStatusReply) {
    if (!reply.hasVerdict || reply.checkedAtMs <= 0L) return
    if (reply.endpoint.isBlank() || reply.endpoint != state.value.configuredEndpoint) return
    mutableState.update {
        it.copy(
            serverChecking = false,
            serverReachable = reply.reachable,
            serverCheckResult = reply.message.ifBlank { it.serverCheckResult },
            serverCheckedAtMs = reply.checkedAtMs,
        )
    }
}

internal fun RuntimeRepository.readRuntimeStatus() {
    if (!state.value.runtimeConnected) return
    connection.execute(
        { RuntimeControlClient(it).status() },
        { applyRuntimeStatus(it) },
        {},
    )
}

internal fun RuntimeRepository.runServerProbe(manual: Boolean) {
    if (serverProbeInFlight) return
    pendingServerCheck = null
    val showProgress =
        ServerProbePolicy.showsProgress(
            manual = manual,
            hasVerdict =
                state.value.serverReachable != null || state.value.serverCheckedAtMs > 0L,
        )
    val generation = ++serverCheckGeneration
    serverProbeInFlight = true
    mutableState.update {
        it.copy(
            serverChecking = showProgress,
            serverCheckResult = if (showProgress) "正在检测已保存的服务器…" else it.serverCheckResult,
        )
    }
    connection.executeProbe(
        { RuntimeControlClient(it).checkServer(force = manual) },
        { result ->
            // A superseded probe must not release the guard held by the one that replaced it.
            if (generation == serverCheckGeneration) {
                serverProbeInFlight = false
                mutableState.update {
                    it.copy(
                        serverChecking = false,
                        serverReachable = result.first,
                        serverCheckResult = result.second,
                        serverCheckedAtMs = System.currentTimeMillis(),
                    )
                }
            }
        },
        { error ->
            if (generation == serverCheckGeneration) {
                serverProbeInFlight = false
                mutableState.update {
                    it.copy(
                        serverChecking = false,
                        serverReachable = false,
                        serverCheckResult =
                            "检测未完成：${OperationFailure.describe(error, crossProcess = true)}",
                        serverCheckedAtMs = System.currentTimeMillis(),
                    )
                }
            }
        },
    )
}

internal fun RuntimeRepository.requestServerCheck() {
    pendingServerCheck = pendingServerCheck ?: false
    refreshConnectionSummary()
}

internal fun RuntimeRepository.performRefreshConnectionSummary() {
    if (!state.value.runtimeConnected) return
    connection.execute(
        { RuntimeControlClient(it).address() },
        { address ->
            mutableState.update {
                it.copy(configuredEndpoint = address, connectionSummaryLoaded = true)
            }
            pendingServerCheck?.let { startServerCheck(manual = it) }
        },
        { mutableState.update { it.copy(connectionSummaryLoaded = false) } },
    )
}

internal fun RuntimeRepository.performNotificationSettings(
    value: NotificationSettings?,
    onFailure: () -> Unit,
    onComplete: (NotificationSettings) -> Unit,
) {
    if (!ready()) {
        onFailure()
        return
    }
    connection.execute(
        { RuntimeControlClient(it).notificationSettings(value) },
        onComplete,
        { error ->
            setStatus(
                OperationStatus.result(
                    "通知设置保存或读取失败：${OperationFailure.describe(error, crossProcess = true)}"
                )
            )
            onFailure()
        },
    )
}

internal fun RuntimeRepository.performQuietHours(
    value: QuietHours?,
    onFailure: () -> Unit,
    onComplete: (QuietHours) -> Unit,
) {
    if (!ready()) {
        onFailure()
        return
    }
    connection.execute(
        { RuntimeControlClient(it).quietHours(value) },
        onComplete,
        { error ->
            setStatus(
                OperationStatus.result(
                    "通知规则保存或读取失败：${OperationFailure.describe(error, crossProcess = true)}"
                )
            )
            onFailure()
        },
    )
}

internal fun RuntimeRepository.performBindDevice(request: BindingRequest, onBound: () -> Unit) {
    if (state.value.messageBusy || !ready()) return
    try {
        request.validate(BuildConfig.DEBUG)
    } catch (_: Exception) {
        setStatus(OperationStatus.result("请输入有效的服务器地址和 32 位绑定码"))
        return
    }
    updateConnection(
        "正在绑定服务器",
        request.endpoint,
        { RuntimeControlClient(it).bindDevice(request) },
        onBound,
        "绑定调用未完成；现有消息保留，请检查配置，必要时重新生成绑定码",
    )
}

internal fun RuntimeRepository.performRotateCredentials(payload: String, onRotated: () -> Unit) {
    if (state.value.messageBusy || !ready()) return
    try {
        io.github.beacon.app.data.remote.RotatedCredentialsDecoder.decode(payload)
    } catch (_: Exception) {
        setStatus(OperationStatus.result("请粘贴服务器输出的完整轮换 JSON"))
        return
    }
    updateConnection(
        "正在验证并更新凭证",
        null,
        { RuntimeControlClient(it).rotateCredentials(payload) },
        onRotated,
        "尚未确认凭证更新结果；请刷新服务器状态后检查，勿重复生成绑定码",
    )
}

internal fun RuntimeRepository.updateConnection(
    progress: String,
    newEndpoint: String?,
    operation: (android.os.IBinder) -> io.github.beacon.app.ipc.RuntimeControlReply,
    onUpdated: () -> Unit,
    failure: String,
) {
    val context = connection.context ?: return
    invalidateServerCheck()
    mutableState.update {
        it.copy(
            transportBusy = true,
            serverChecking = false,
            status = OperationStatus.pending(progress),
        )
    }
    connection.execute(
        { binder ->
            operation(binder).let { result ->
                if (result.outcome == SyncOutcome.OK)
                    result.copy(text = reconcile(binder, result.text))
                else result
            }
        },
        { result ->
            mutableState.update {
                it.copy(
                    transportBusy = false,
                    pollingState = result.state,
                    status = OperationStatus.result(result.text),
                )
            }
            if (result.outcome == SyncOutcome.OK) {
                mutableState.update {
                    it.copy(
                        configuredEndpoint = newEndpoint ?: it.configuredEndpoint,
                        connectionSummaryLoaded = true,
                        serverReachable = null,
                        serverCheckedAtMs = 0,
                        lastManualOutcome = null,
                    )
                }
                onUpdated()
                launchRealtime()
                requestServerCheck()
            } else if (state.value.configuredEndpoint.isNotBlank()) {
                requestServerCheck()
            }
        },
        { failTransport(failure) },
    )
}

internal fun RuntimeRepository.performCopyPushKey(onReady: (String) -> Unit) {
    if (state.value.messageBusy || !ready()) return
    connection.execute(
        { RuntimeControlClient(it).pushKey() },
        { address ->
            onReady(address)
            setStatus(OperationStatus.result("推送密钥已复制，请仅交给可信的推送脚本"))
        },
        { setStatus(OperationStatus.result("当前配置没有保存推送密钥；使用绑定码绑定后可复制，旧设备请使用服务器保存的推送密钥")) },
    )
}
