package io.github.beacon.app.data.repository

import android.content.Context
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.OperationFailure
import io.github.beacon.app.domain.policy.RealtimeRetryDecision
import io.github.beacon.app.domain.policy.ServerProbePolicy
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ipc.RuntimeConnection
import io.github.beacon.app.ipc.RuntimeControlClient
import io.github.beacon.app.ipc.RuntimeEventClient
import io.github.beacon.app.ipc.RuntimeMessageClient
import io.github.beacon.app.ipc.RuntimeProtocol
import io.github.beacon.app.ipc.request
import io.github.beacon.app.runtime.RuntimeProbeService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Coordinates application operations; binding and the wire format live in ipc. */
class RuntimeRepository : RuntimeActions {
    private val mutableState = MutableStateFlow(RuntimeState())
    override val state = mutableState.asStateFlow()
    private var pageHistory = listOf(Long.MAX_VALUE)
    private var detailGeneration = 0L
    private var backgroundAnchor: MessageKey? = null
    private var backgroundListReleased = false
    private var preserveReadingWindow = false
    private var receivePlanInFlight = false
    private var receivePlanRefreshPending = false
    private var receivePlanRefreshJob: kotlinx.coroutines.Job? = null
    private var pendingMessageRefresh = false
    private val refreshScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
        )

    init {
        refreshScope.launch {
            state
                .map { it.messageBusy }
                .distinctUntilChanged()
                .collect { busy ->
                    if (!busy && pendingMessageRefresh) {
                        pendingMessageRefresh = false
                        receivePlan()
                    }
                }
        }
    }

    fun saveReadingAnchor(key: MessageKey?) {
        backgroundAnchor = key
    }

    /**
     * Check owed to [refreshConnectionSummary]: null when none is pending, true when the user asked
     * for it, false when it came from returning to the foreground. A manual request wins, so a
     * pending foreground re-check never downgrades an explicit one into a silent probe.
     */
    private var pendingServerCheck: Boolean? = null
    private var serverCheckGeneration = 0L

    /**
     * An automatic probe leaves [RuntimeState.serverChecking] false, so in-flight probes need their
     * own guard; otherwise rapid foreground entries would stack duplicate requests.
     */
    private var serverProbeInFlight = false

    /** Drops any owed or in-flight probe whose answer is no longer relevant. */
    private fun invalidateServerCheck() {
        serverCheckGeneration++
        serverProbeInFlight = false
        pendingServerCheck = null
    }

    private val connection =
        RuntimeConnection(
            serviceClass = RuntimeProbeService::class.java,
            onStatus = { status, connected ->
                if (!connected) {
                    invalidateServerCheck()
                    receivePlanInFlight = false
                    receivePlanRefreshPending = false
                    receivePlanRefreshJob?.cancel()
                    receivePlanRefreshJob = null
                    pendingMessageRefresh = false
                }
                mutableState.update {
                    it.copy(
                        status = status,
                        runtimeConnected = connected,
                        dataBusy = if (connected) it.dataBusy else false,
                        transportBusy = if (connected) it.transportBusy else false,
                        serverChecking = if (connected) it.serverChecking else false,
                        cleanupPreview = if (connected) it.cleanupPreview else null,
                    )
                }
            },
            onConnected = {
                // Queue the local first page before control reads. Their callbacks can enqueue
                // network probes and schedule reconciliation on this same serial IPC executor.
                val current = state.value
                preserveReadingWindow = backgroundListReleased && backgroundAnchor != null
                loadPage(
                    current.messageQuery.copy(
                        anchor = if (backgroundListReleased) backgroundAnchor else null
                    ),
                    listOf(Long.MAX_VALUE),
                    0,
                    showStatus = false,
                )
                backgroundListReleased = false
                refreshConnectionSummary()
                refreshPolling()
                executeReceivePlan(null)
            },
            onRuntimeChanged = { scheduleReceivePlanRefresh() },
        )

    override fun start(context: Context) = connection.start(context)

    override fun barkEncryption(key: String, iv: String, onComplete: (String) -> Unit) {
        if (!ready() || state.value.transportBusy) {
            onComplete("请等待接收服务就绪")
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
                onComplete("保存未确认，请检查配置后重试")
            },
        )
    }

    override fun testDelivery(onFailure: (String) -> Unit, onComplete: (String) -> Unit) {
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

    override fun historyArchive(
        uri: String,
        password: String,
        importing: Boolean,
        encrypted: Boolean,
        onFailure: (String) -> Unit,
        onComplete: (String) -> Unit,
    ) {
        if (!ready() || state.value.messageBusy || state.value.transportBusy) {
            onFailure("接收服务未就绪，请稍后重试")
            return
        }
        mutableState.update { it.copy(transportBusy = true) }
        connection.execute(
            { RuntimeControlClient(it).historyArchive(uri, password, importing, encrypted) },
            { result ->
                mutableState.update { it.copy(transportBusy = false) }
                if (importing) readMessages()
                onComplete(result)
            },
            {
                mutableState.update { it.copy(transportBusy = false) }
                onFailure(
                    if (importing) "导入失败或结果未确认：请检查密码、文件与当前消息；重复导入不会覆盖已有消息" else "导出失败，请检查文件权限与剩余空间"
                )
            },
        )
    }

    override fun settingsBackup(
        payload: String?,
        onFailure: () -> Unit,
        onComplete: (String) -> Unit,
    ) {
        if (state.value.messageBusy || state.value.transportBusy || !ready()) {
            onFailure()
            return
        }
        mutableState.update { it.copy(transportBusy = true) }
        connection.execute(
            { RuntimeControlClient(it).settingsBackup(payload) },
            { result ->
                mutableState.update { it.copy(transportBusy = false) }
                if (payload != null) {
                    refreshPolling()
                    receivePlan()
                }
                onComplete(result)
            },
            { error ->
                mutableState.update { it.copy(transportBusy = false) }
                if (payload != null) {
                    refreshPolling()
                    receivePlan()
                }
                setStatus(
                    OperationStatus.result(
                        "设置备份操作未完成（${OperationFailure.describe(error, crossProcess = true)}），请检查当前设置"
                    )
                )
                onFailure()
            },
        )
    }

    override fun stop(status: OperationStatus) = connection.stop(status)

    fun close() {
        refreshScope.cancel()
        connection.close()
    }

    /** Called only after the idle UI composition is disposed and its binding is released. */
    fun releaseBackgroundDetail() {
        check(!connection.connected)
        closeDetail()
        backgroundListReleased = true
        pageHistory = listOf(Long.MAX_VALUE)
        mutableState.update {
            it.copy(
                messages = emptyList(),
                messagesLoaded = false,
                nextMessageCursor = null,
                cleanupPreview = null,
            )
        }
    }

    private fun setStatus(status: OperationStatus) {
        mutableState.update { it.copy(status = status) }
    }

    private fun ready(): Boolean {
        if (!connection.connected) {
            setStatus(OperationStatus.connectionLost("请先连接接收服务"))
            return false
        }
        return true
    }

    override fun revokeDevice(onRevoked: () -> Unit) {
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

    override fun clearConnection(onCleared: () -> Unit) {
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

    override fun eventLog(onFailure: () -> Unit, onComplete: (List<RuntimeEvent>) -> Unit) {
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

    override fun clearEventLog(onFailure: () -> Unit, onComplete: () -> Unit) {
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

    override fun reconnectRealtime() {
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

    override fun checkServer() = startServerCheck(manual = true)

    override fun autoCheckServer() = startServerCheck(manual = false)

    /**
     * Probes the saved server. The probe itself always runs; only its visible progress depends on
     * [ServerProbePolicy], so the toolbar entry and the status card keep the previous verdict
     * during an automatic re-check instead of flashing "checking" on every return to the
     * foreground.
     */
    private fun startServerCheck(manual: Boolean) {
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
        if (serverProbeInFlight) return
        pendingServerCheck = null
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
            { RuntimeControlClient(it).checkServer() },
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

    /**
     * Records the owed check and re-reads the saved address before running it. The follow-up probe
     * is automatic: a config save already reset the displayed verdict, so it needs no "checking"
     * state.
     */
    private fun requestServerCheck() {
        pendingServerCheck = pendingServerCheck ?: false
        refreshConnectionSummary()
    }

    override fun refreshConnectionSummary() {
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

    override fun notificationSettings(
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

    override fun quietHours(
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

    override fun bindDevice(request: BindingRequest, onBound: () -> Unit) {
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

    override fun rotateCredentials(payload: String, onRotated: () -> Unit) {
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

    private fun updateConnection(
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

    override fun copyPushKey(onReady: (String) -> Unit) {
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

    override fun readMessages(
        injectTest: Boolean,
        delete: IncomingMessage?,
        search: String,
        serverId: String,
        readFilter: MessageReadFilter,
        showStatus: Boolean,
    ) {
        if (!state.value.messageBusy) preserveReadingWindow = false
        loadPage(
            MessageQuery(search, serverId, readFilter = readFilter),
            listOf(Long.MAX_VALUE),
            0,
            injectTest,
            delete,
            showStatus,
        )
    }

    override fun nextMessages() {
        val current = state.value
        val cursor = current.nextMessageCursor ?: return
        loadPage(
            current.messageQuery.copy(before = cursor),
            pageHistory.take(current.messagePageIndex + 1) + cursor,
            current.messagePageIndex + 1,
            showStatus = false,
        )
    }

    override fun previousMessages() {
        val current = state.value
        if (current.messagePageIndex <= 0) return
        val index = current.messagePageIndex - 1
        loadPage(current.messageQuery.copy(before = pageHistory[index]), pageHistory, index)
    }

    private fun loadPage(
        query: MessageQuery,
        history: List<Long>,
        index: Int,
        injectTest: Boolean = false,
        delete: IncomingMessage? = null,
        showStatus: Boolean = true,
        deliveryRevision: Long? = null,
    ) {
        if (state.value.messageBusy) return
        try {
            query.validate()
        } catch (_: IllegalArgumentException) {
            setStatus(OperationStatus.result("搜索词最多 256 个字符"))
            return
        }
        if (!ready()) return
        mutableState.update { it.copy(dataBusy = true, messageLoadFailed = false) }
        connection.execute(
            { binder ->
                val client = RuntimeMessageClient(binder)
                if (injectTest || delete != null) client.injectOrDelete(delete)
                client.query(query)
            },
            { result ->
                pageHistory = history
                mutableState.update {
                    it.copy(
                        messages =
                            if (
                                index > it.messagePageIndex &&
                                    query.search == it.messageQuery.search &&
                                    query.readFilter == it.messageQuery.readFilter &&
                                    query.serverId == it.messageQuery.serverId
                            )
                                (it.messages + result.page.items)
                                    .distinctBy { message ->
                                        MessageKey(message.server, message.id)
                                    }
                                    .takeLast(500)
                            else result.page.items,
                        messageTotal = result.page.total,
                        messageUnread = result.page.unread,
                        nextMessageCursor = result.page.nextBefore,
                        messageQuery = query.copy(before = Long.MAX_VALUE, anchor = null),
                        messagePageIndex = index,
                        messagesLoaded = true,
                        deliveryRevision = deliveryRevision ?: it.deliveryRevision,
                        dataBusy = false,
                        status =
                            if (showStatus)
                                OperationStatus.diagnostic(
                                    "已读取第 ${index + 1} 页：${result.page.items.size} 条，匹配共 ${result.page.total} 条"
                                )
                            else it.status,
                    )
                }
            },
            { error ->
                failData(
                    "消息操作失败（${OperationFailure.describe(error, crossProcess = true)}）；已显示数据保留",
                    loadFailed = true,
                )
            },
        )
    }

    override fun remindMessage(key: MessageKey) {
        key.validate()
        if (state.value.messageBusy || !ready()) return
        mutableState.update { it.copy(dataBusy = true) }
        connection.execute(
            { RuntimeMessageClient(it).remind(key) },
            { result ->
                mutableState.update { it.copy(dataBusy = false) }
                setStatus(OperationStatus.result(result))
            },
            { error ->
                mutableState.update { it.copy(dataBusy = false) }
                setStatus(
                    OperationStatus.result(
                        "重新提醒未完成（${OperationFailure.describe(error,crossProcess=true)}）；消息保留"
                    )
                )
            },
        )
    }

    override fun closeDetail() {
        detailGeneration++
        mutableState.update { it.copy(selectedMessage = null) }
    }

    override fun loadDetail(message: IncomingMessage, onLoaded: ((IncomingMessage) -> Unit)?) {
        readDetail(MessageKey(message.server, message.id), onLoaded)
    }

    override fun openMessage(key: MessageKey, onComplete: () -> Unit) {
        key.validate()
        readDetail(key, null, onComplete)
    }

    private fun readDetail(
        key: MessageKey,
        onLoaded: ((IncomingMessage) -> Unit)?,
        onComplete: () -> Unit = {},
    ) {
        if (state.value.messageBusy || !ready()) return
        val request = if (onLoaded == null) ++detailGeneration else detailGeneration
        mutableState.update { it.copy(dataBusy = true) }
        connection.execute(
            { RuntimeMessageClient(it).detail(key) },
            { full ->
                mutableState.update { it.copy(dataBusy = false) }
                onComplete()
                if (onLoaded == null && request != detailGeneration) return@execute
                when {
                    full == null -> setStatus(OperationStatus.result("消息已不存在，请刷新列表"))
                    onLoaded != null -> onLoaded(full)
                    else -> {
                        mutableState.update { it.copy(selectedMessage = full) }
                        if (!full.isRead)
                            manageMessages(
                                MessageAction.MARK_READ,
                                listOf(MessageKey(full.server, full.id)),
                                true,
                            )
                    }
                }
            },
            { error ->
                mutableState.update { it.copy(dataBusy = false) }
                onComplete()
                if (onLoaded != null || request == detailGeneration)
                    setStatus(
                        OperationStatus.result(
                            "详情读取失败（${OperationFailure.describe(error, crossProcess = true)}）"
                        )
                    )
            },
        )
    }

    override fun manageMessages(
        action: MessageAction,
        keys: List<MessageKey>,
        preservePage: Boolean,
        onComplete: (() -> Unit)?,
    ) {
        val request = MessageMutation(action, keys.toList())
        try {
            request.validate()
        } catch (_: IllegalArgumentException) {
            setStatus(OperationStatus.result("请选择 1–100 条不同的消息"))
            return
        }
        manage(request, preservePage, onComplete)
    }

    override fun cleanupSettings(
        value: io.github.beacon.app.domain.model.CleanupSettings?,
        onFailure: () -> Unit,
        onComplete: (io.github.beacon.app.domain.model.CleanupSettings) -> Unit,
    ) {
        if (!ready()) {
            onFailure()
            return
        }
        connection.execute(
            { RuntimeMessageClient(it).cleanupSettings(value) },
            onComplete,
            { error ->
                setStatus(
                    OperationStatus.result(
                        "清理设置保存或读取失败：${OperationFailure.describe(error,crossProcess=true)}"
                    )
                )
                onFailure()
            },
        )
    }

    override fun previewCleanup(policy: CleanupPolicy) {
        if (state.value.messageBusy || !ready()) return
        policy.validate()
        mutableState.update { it.copy(dataBusy = true, cleanupPreview = null) }
        connection.execute(
            { RuntimeMessageClient(it).previewCleanup(policy) },
            { preview ->
                mutableState.update {
                    it.copy(dataBusy = false, cleanupPreview = preview, cleanupPolicy = policy)
                }
            },
            { error -> failData("清理预览失败：${OperationFailure.describe(error,crossProcess=true)}") },
        )
    }

    override fun pendingReminders(onFailure: () -> Unit, onReady: (Long) -> Unit) {
        if (!ready()) {
            onFailure()
            return
        }
        connection.execute(
            { RuntimeMessageClient(it).pendingReminderCount() },
            onReady,
            {
                setStatus(OperationStatus.result("待通知数量读取失败，请重试"))
                onFailure()
            },
        )
    }

    override fun checkpointStorage() {
        if (state.value.messageBusy || !ready()) return
        mutableState.update { it.copy(dataBusy = true) }
        connection.execute(
            { RuntimeMessageClient(it).checkpointStorage() },
            { complete ->
                mutableState.update { it.copy(dataBusy = false) }
                setStatus(
                    OperationStatus.result(if (complete) "临时日志已清理，消息与接收记录保留" else "临时日志暂时被占用，请稍后重试")
                )
                state.value.cleanupPolicy?.let(::previewCleanup)
            },
            { error ->
                failData("临时日志清理失败：${OperationFailure.describe(error, crossProcess = true)}")
            },
        )
    }

    override fun markAllRead() {
        if (state.value.messageBusy || !ready()) return
        mutableState.update { it.copy(dataBusy = true) }
        connection.execute(
            { RuntimeMessageClient(it).markAllRead() },
            { changed ->
                mutableState.update {
                    it.copy(
                        dataBusy = false,
                        cleanupPreview = null,
                        selectedMessage = it.selectedMessage?.copy(isRead = true),
                        status = OperationStatus.result("已标为已读 $changed 条"),
                    )
                }
                val query = state.value.messageQuery
                readMessages(
                    search = query.search,
                    serverId = query.serverId,
                    readFilter = query.readFilter,
                    showStatus = false,
                )
            },
            { error ->
                failData("全部已读未完成，请刷新核对：${OperationFailure.describe(error, crossProcess = true)}")
            },
        )
    }

    override fun clearAllMessages() {
        if (state.value.messageBusy || !ready()) return
        mutableState.update { it.copy(dataBusy = true) }
        connection.execute(
            { RuntimeMessageClient(it).clearAllMessages() },
            { count ->
                mutableState.update {
                    it.copy(
                        dataBusy = false,
                        cleanupPreview = null,
                        selectedMessage = null,
                        status = OperationStatus.result("已清理全部 $count 条本地消息；接收确认与去重记录保留"),
                    )
                }
                readMessages(showStatus = false)
            },
            { failData("清理结果未确认，请刷新核对；不会清除服务器配置") },
        )
    }

    override fun applyCleanup(token: String) {
        if (state.value.messageBusy || !ready()) return
        mutableState.update { it.copy(dataBusy = true, cleanupPreview = null) }
        connection.execute(
            { RuntimeMessageClient(it).applyCleanup(token) },
            { result ->
                mutableState.update {
                    it.copy(
                        dataBusy = false,
                        cleanupPreview = null,
                        status = OperationStatus.result("已清理 ${result.changed} 条本地消息"),
                    )
                }
                readMessages(showStatus = false)
            },
            { error ->
                failData("清理未完成，请重新预览：${OperationFailure.describe(error,crossProcess=true)}")
            },
        )
    }

    private fun manage(
        request: MessageMutation,
        preservePage: Boolean,
        onComplete: (() -> Unit)?,
    ) {
        if (state.value.messageBusy || !ready()) return
        mutableState.update {
            it.copy(dataBusy = true, status = OperationStatus.pending("正在处理消息管理操作"))
        }
        connection.execute(
            { RuntimeMessageClient(it).mutate(request) },
            { result ->
                mutableState.update { current ->
                    val selected = current.selectedMessage
                    val changedSelection =
                        selected != null &&
                            request.keys.contains(MessageKey(selected.server, selected.id)) == true
                    val selectedMessage =
                        if (!changedSelection) selected
                        else if (request.action == MessageAction.DELETE) null
                        else selected.copy(isRead = request.action == MessageAction.MARK_READ)
                    val text =
                        when (request.action) {
                            MessageAction.MARK_READ -> "已标为已读 ${result.changed} 条；待通知与接收确认记录保留"
                            MessageAction.MARK_UNREAD -> "已标为未读 ${result.changed} 条；不会重新提醒"
                            MessageAction.DELETE -> "已删除 ${result.changed} 条本地消息"
                        }
                    current.copy(
                        dataBusy = false,
                        selectedMessage = selectedMessage,
                        status = OperationStatus.result(text),
                    )
                }
                onComplete?.invoke()
                if (preservePage && request.action != MessageAction.DELETE) {
                    mutableState.update { current ->
                        val isRead = request.action == MessageAction.MARK_READ
                        val delta = if (isRead) -result.changed else result.changed
                        val totalDelta =
                            when (current.messageQuery.readFilter) {
                                MessageReadFilter.ALL -> 0
                                MessageReadFilter.UNREAD -> delta
                                MessageReadFilter.READ -> -delta
                            }
                        current.copy(
                            messages =
                                current.messages
                                    .map {
                                        if (MessageKey(it.server, it.id) in request.keys)
                                            it.copy(isRead = isRead)
                                        else it
                                    }
                                    .filter {
                                        when (current.messageQuery.readFilter) {
                                            MessageReadFilter.ALL -> true
                                            MessageReadFilter.UNREAD -> !it.isRead
                                            MessageReadFilter.READ -> it.isRead
                                        }
                                    },
                            messageUnread =
                                if (current.messageQuery.readFilter == MessageReadFilter.READ) 0
                                else (current.messageUnread + delta).coerceAtLeast(0),
                            messageTotal = (current.messageTotal + totalDelta).coerceAtLeast(0),
                        )
                    }
                } else readMessages(showStatus = false)
            },
            { error ->
                failData(
                    "消息管理失败（${OperationFailure.describe(error, crossProcess = true)}）；请刷新核对实际状态"
                )
            },
        )
    }

    private fun failData(
        message: String,
        kind: OperationKind = OperationKind.RESULT,
        loadFailed: Boolean = false,
    ) {
        mutableState.update {
            it.copy(
                dataBusy = false,
                messageLoadFailed = it.messageLoadFailed || loadFailed,
                status = OperationStatus(kind, message),
            )
        }
    }

    private fun failTransport(message: String, kind: OperationKind = OperationKind.RESULT) {
        mutableState.update {
            it.copy(transportBusy = false, status = OperationStatus(kind, message))
        }
    }

    override fun transport(
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
                if (settings == null && !notifyOnly) readMessages(showStatus = false)
            },
            { error ->
                failTransport(
                    "连接操作失败（${OperationFailure.describe(error, crossProcess = true)}）；配置与已保存消息保留"
                )
            },
        )
    }

    override fun refreshPolling(onComplete: ((String) -> Unit)?) {
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

    override fun setPolling(config: PollingConfig) {
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

    override fun saveReception(plan: ReceivePlan, polling: PollingConfig) {
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

    private fun scheduleReceivePlanRefresh() {
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

    override fun receivePlan(value: ReceivePlan?) {
        if (value == null) scheduleReceivePlanRefresh() else executeReceivePlan(value)
    }

    private fun executeReceivePlan(value: ReceivePlan?) {
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
                        if (preserveReadingWindow) {
                            current.messages.firstOrNull()?.let { MessageKey(it.server, it.id) }
                        } else null
                    loadPage(
                        current.messageQuery.copy(anchor = anchor),
                        listOf(Long.MAX_VALUE),
                        0,
                        showStatus = false,
                        deliveryRevision = result.deliveryRevision,
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

    private fun launchRealtime() {
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

    private fun reconcile(binder: android.os.IBinder, text: String): String =
        try {
            binder.request(RuntimeProtocol.RECONCILE_POLLING) { Unit }
            text
        } catch (error: Exception) {
            listOf(
                    text,
                    "接收设置已保存，但系统调度未完成（${OperationFailure.describe(error)}）；请点击读取接收设置重试",
                )
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }

    override fun scheduleOneMinuteTest() {
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
}
