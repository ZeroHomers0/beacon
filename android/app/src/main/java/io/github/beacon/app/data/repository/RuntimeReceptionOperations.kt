package io.github.beacon.app.data.repository

import io.github.beacon.app.BuildConfig
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.OperationFailure
import io.github.beacon.app.ipc.RuntimeControlClient
import io.github.beacon.app.ipc.RuntimeProtocol
import io.github.beacon.app.ipc.request
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** reception use cases; repository remains the lifecycle/state owner. */
internal fun RuntimeRepository.performTransport(
    settings: TransportSettings?,
    notifyOnly: Boolean,
    onConfigured: (() -> Unit)?,
) {
    if (state.value.messageBusy || !ready()) return
    val context = connection.context ?: return
    if (settings != null) {
        invalidateServerCheck()
        mutableState.update {
            it.copy(
                serverChecking = false,
                serverReachable = null,
                serverCheckResult = "配置更新后将重新检测",
                serverCheckedAtMs = 0L,
            )
        }
    }
    mutableState.update {
        it.copy(
            transportBusy = true,
            status =
                OperationStatus.pending(
                    if (notifyOnly) "正在处理待通知" else if (settings == null) "正在同步" else "正在保存连接配置"
                ),
        )
    }
    connection.execute(
        { binder ->
            val result = RuntimeControlClient(binder).transport(settings, notifyOnly)
            result.copy(text = reconcile(binder, result.text))
        },
        { result ->
            mutableState.update {
                it.copy(
                    transportBusy = false,
                    status = OperationStatus.result(result.text),
                    pollingState = result.state,
                    lastManualOutcome =
                        when {
                            settings != null -> null
                            !notifyOnly -> result.outcome
                            else -> it.lastManualOutcome
                        },
                    configuredEndpoint = settings?.endpoint ?: it.configuredEndpoint,
                    connectionSummaryLoaded = settings != null || it.connectionSummaryLoaded,
                )
            }
            if (settings != null) {
                requestServerCheck()
                onConfigured?.invoke()
            }
            if (!notifyOnly && result.outcome == SyncOutcome.OK) launchRealtime()
            if (settings == null && !notifyOnly) readMessages(showStatus = false)
        },
        { error ->
            failTransport(
                "连接操作失败（${OperationFailure.describe(error, crossProcess = true)}）；配置与已保存消息保留"
            )
        },
    )
}

internal fun RuntimeRepository.performRefreshPolling(onComplete: ((String) -> Unit)?) {
    // Foreground effects may run while PING is still validating a new binding. Initial
    // control reads are queued by onConnected, after the first local page.
    if (!state.value.runtimeConnected && onComplete == null) return
    val context =
        connection.context
            ?: run {
                onComplete?.invoke("接收服务尚未连接，无法更新状态")
                return
            }
    connection.execute(
        { binder ->
            val state = RuntimeControlClient(binder).state()
            state to reconcile(binder, "")
        },
        { (state, text) ->
            mutableState.update {
                it.copy(
                    pollingState = state,
                    status =
                        if (text.isEmpty()) it.status else OperationStatus.diagnostic(text),
                )
            }
            launchRealtime()
            onComplete?.invoke(
                if (text.isNotEmpty()) text
                else
                    "运行状态已更新 · ${if (state.config.enabled) "自动接收已开启" else "自动接收已暂停"}；此操作不接收新消息。"
            )
        },
        { error ->
            onComplete?.invoke(
                "刷新状态失败（${OperationFailure.describe(error, crossProcess = true)}）"
            )
        },
    )
}

internal fun RuntimeRepository.performSetPolling(config: PollingConfig) {
    if (state.value.messageBusy || !ready()) return
    val context = connection.context ?: return
    mutableState.update {
        it.copy(
            transportBusy = true,
            status = OperationStatus.pending("正在保存接收设置"),
        )
    }
    connection.execute(
        { binder ->
            val result = RuntimeControlClient(binder).setPolling(config)
            result.copy(text = reconcile(binder, result.text))
        },
        { result ->
            mutableState.update {
                it.copy(
                    pollingState = result.state,
                    transportBusy = false,
                    status = OperationStatus.result(result.text),
                )
            }
            launchRealtime()
        },
        { error ->
            failTransport("接收设置失败（${OperationFailure.describe(error, crossProcess = true)}）")
        },
    )
}

internal fun RuntimeRepository.performSaveReception(plan: ReceivePlan, polling: PollingConfig) {
    if (state.value.messageBusy || !ready()) return
    plan.validate()
    polling.validate()
    val current = state.value
    mutableState.update { it.copy(transportBusy = true) }
    connection.execute(
        { binder ->
            val client = RuntimeControlClient(binder)
            // Apply both halves on the same serial executor, then read the confirmed snapshot.
            if (polling != current.pollingState.config) client.setPolling(polling)
            val confirmed = client.receivePlan(if (plan != current.receivePlan) plan else null)
            binder.request(RuntimeProtocol.RECONCILE_POLLING) { Unit }
            confirmed
        },
        { result ->
            mutableState.update {
                it.copy(
                    transportBusy = false,
                    receivePlan = result.plan,
                    receivePlanLoaded = true,
                    pollingState = result.polling,
                    realtimeStatus = result.status,
                    realtimeRunning = result.running,
                    realtimeDiagnostics = result.diagnostics,
                    recovery = result.recovery,
                )
            }
            launchRealtime()
            receivePlan()
        },
        { error ->
            failTransport(
                "接收设置保存或调度失败：${OperationFailure.describe(error, crossProcess = true)}"
            )
            receivePlan()
        },
    )
}

internal fun RuntimeRepository.scheduleReceivePlanRefresh() {
    if (!state.value.runtimeConnected || !connection.connected) return
    if (receivePlanInFlight) {
        // A hint during a read may describe a newer delivery: keep one trailing read.
        receivePlanRefreshPending = true
        return
    }
    if (receivePlanRefreshJob != null) return
    receivePlanRefreshJob = refreshScope.launch {
        // Fixed window rather than a resetting debounce: a steady stream cannot starve
        // message updates. Startup reads and mode writes remain immediate. This batches
        // UI callers too, in addition to the service's existing broadcast coalescing.
        kotlinx.coroutines.delay(75)
        receivePlanRefreshJob = null
        executeReceivePlan(null)
    }
}

internal fun RuntimeRepository.performReceivePlan(value: ReceivePlan?) {
    if (value == null) scheduleReceivePlanRefresh() else executeReceivePlan(value)
}

internal fun RuntimeRepository.executeReceivePlan(value: ReceivePlan?) {
    if (!state.value.runtimeConnected || !connection.connected) return
    receivePlanRefreshJob?.cancel()
    receivePlanRefreshJob = null
    if (value == null && receivePlanInFlight) {
        receivePlanRefreshPending = true
        return
    }
    if (value != null && state.value.messageBusy) return
    receivePlanInFlight = true
    connection.execute(
        { RuntimeControlClient(it).receivePlan(value) },
        { result ->
            receivePlanInFlight = false
            val refreshAgain = receivePlanRefreshPending
            receivePlanRefreshPending = false
            val changed = result.deliveryRevision != state.value.deliveryRevision
            pendingMessageRefresh = changed && state.value.messageBusy
            val firstRead = !state.value.receivePlanLoaded
            mutableState.update {
                it.copy(
                    receivePlan = result.plan,
                    receivePlanLoaded = true,
                    realtimeStatus = result.status,
                    realtimeRunning = result.running,
                    realtimeDiagnostics = result.diagnostics,
                    recovery = result.recovery,
                    // Only the successful page read consumes a delivery revision. Busy
                    // or failed reads must leave the next hint/fallback able to retry.
                    deliveryRevision = it.deliveryRevision,
                    pollingState = result.polling,
                )
            }
            if (value != null || firstRead) launchRealtime()
            if (changed && !state.value.messageBusy) {
                val current = state.value
                val anchor =
                    if (preserveReadingWindow || readingHistory || current.messagePageIndex>0) {
                        newMessageAnchor ?: current.messages.firstOrNull()?.let { MessageKey(it.server, it.id) }
                    } else null
                loadPage(
                    current.messageQuery.copy(anchor = anchor),
                    listOf(Long.MAX_VALUE),
                    0,
                    showStatus = false,
                    deliveryRevision = result.deliveryRevision,
                    retainWindow = (preserveReadingWindow || readingHistory || current.messagePageIndex>0) && current.messages.isNotEmpty(),
                )
            }
            if (refreshAgain) scheduleReceivePlanRefresh()
        },
        { error ->
            receivePlanInFlight = false
            val refreshAgain = receivePlanRefreshPending
            receivePlanRefreshPending = false
            if (value != null)
                setStatus(
                    OperationStatus.result(
                        "模式保存失败：${OperationFailure.describe(error, crossProcess = true)}"
                    )
                )
            if (refreshAgain) scheduleReceivePlanRefresh()
        },
    )
}

internal fun RuntimeRepository.launchRealtime() {
    val context = connection.context ?: return
    val state = state.value
    if (!state.receivePlanLoaded) return
    try {
        io.github.beacon.app.runtime.RealtimeServiceLauncher.apply(
            context,
            state.receivePlan,
            state.pollingState.shouldSchedule,
        )
    } catch (_: Exception) {
        mutableState.update {
            it.copy(
                realtimeRunning = false,
                realtimeStatus = "后台启动受限，请打开应用并重新启用自动接收；HTTP 补收保留",
            )
        }
    }
}

internal fun RuntimeRepository.performScheduleOneMinuteTest() {
    if (!BuildConfig.DEBUG) return
    val current = state.value
    if (!current.pollingState.shouldSchedule) {
        setStatus(OperationStatus.result("请先开启省电接收，再安排一分钟测试"))
        return
    }
    val context =
        connection.context
            ?: run {
                setStatus(OperationStatus.connectionLost("请先连接数据进程"))
                return
            }
    if (current.transportBusy) return
    setStatus(OperationStatus.pending("正在安排一分钟测试任务"))
    connection.execute(
        { binder -> binder.request(RuntimeProtocol.SCHEDULE_TEST) { Unit } },
        { setStatus(OperationStatus.result("一分钟测试任务已安排；系统可能有少量调度偏差")) },
        { error ->
            setStatus(OperationStatus.result("一分钟测试任务失败（${OperationFailure.describe(error)}）"))
        },
    )
}
