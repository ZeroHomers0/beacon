package io.github.beacon.app.data.repository

import android.content.Context
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.OperationFailure
import io.github.beacon.app.domain.policy.ServerProbePolicy
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ipc.RuntimeConnection
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
    internal val mutableState = MutableStateFlow(RuntimeState())
    override val state = mutableState.asStateFlow()
    internal var pageHistory = listOf(Long.MAX_VALUE)
    internal var detailGeneration = 0L
    internal var backgroundAnchor: MessageKey? = null
    internal var backgroundListReleased = false
    internal var preserveReadingWindow = false
    internal var readingHistory = false
    internal var newMessageAnchor: MessageKey? = null

    override fun setMessageReading(readingHistory: Boolean) {
        this.readingHistory=readingHistory
        if(!readingHistory && state.value.newMessageCount==0L)newMessageAnchor=null
        if(readingHistory && newMessageAnchor==null)newMessageAnchor=state.value.messages.firstOrNull()?.let{MessageKey(it.server,it.id)}
    }
    internal var receivePlanInFlight = false
    internal var receivePlanRefreshPending = false
    internal var receivePlanRefreshJob: kotlinx.coroutines.Job? = null
    internal var pendingMessageRefresh = false
    internal val refreshScope =
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
    internal var pendingServerCheck: Boolean? = null
    internal var serverCheckGeneration = 0L

    /**
     * An automatic probe leaves [RuntimeState.serverChecking] false, so in-flight probes need their
     * own guard; otherwise rapid foreground entries would stack duplicate requests.
     */
    internal var serverProbeInFlight = false

    /** Drops any owed or in-flight probe whose answer is no longer relevant. */
    internal fun invalidateServerCheck() {
        serverCheckGeneration++
        serverProbeInFlight = false
        pendingServerCheck = null
    }

    internal val connection =
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
                readRuntimeStatus()
            },
            onRuntimeChanged = { scheduleReceivePlanRefresh() },
        )

    override fun start(context: Context) = connection.start(context)

    override fun barkEncryptionConfigured(onComplete: (Boolean?) -> Unit) {
        performBarkEncryptionConfigured(onComplete)
    }

    override fun barkEncryption(key: String, iv: String, onFailure: (String) -> Unit, onComplete: (String) -> Unit) {
        performBarkEncryption(key, iv, onFailure, onComplete)
    }

    override fun testDelivery(onFailure: (String) -> Unit, onComplete: (String) -> Unit) {
        performTestDelivery(onFailure, onComplete)
    }

    override fun historyArchive(
        uri: String,
        password: String,
        importing: Boolean,
        encrypted: Boolean,
        onFailure: (String) -> Unit,
        onComplete: (String) -> Unit,
    ) {
        performHistoryArchive(uri, password, importing, encrypted, onFailure, onComplete)
    }

    override fun settingsBackup(
        payload: String?,
        onFailure: () -> Unit,
        onComplete: (String) -> Unit,
    ) {
        performSettingsBackup(payload, onFailure, onComplete)
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

    internal fun setStatus(status: OperationStatus) {
        mutableState.update { it.copy(status = status) }
    }

    internal fun ready(): Boolean {
        if (!connection.connected) {
            setStatus(OperationStatus.connectionLost("请先连接接收服务"))
            return false
        }
        return true
    }

    override fun revokeDevice(onRevoked: () -> Unit) {
        performRevokeDevice(onRevoked)
    }

    override fun clearConnection(onCleared: () -> Unit) {
        performClearConnection(onCleared)
    }

    override fun eventLog(onFailure: () -> Unit, onComplete: (List<RuntimeEvent>) -> Unit) {
        performEventLog(onFailure, onComplete)
    }

    override fun clearEventLog(onFailure: () -> Unit, onComplete: () -> Unit) {
        performClearEventLog(onFailure, onComplete)
    }

    override fun reconnectRealtime() {
        performReconnectRealtime()
    }

    override fun checkServer() = startServerCheck(manual = true)

    override fun autoCheckServer() = startServerCheck(manual = false)

    /**
     * Probes the saved server. The probe itself always runs; only its visible progress depends on
     * [ServerProbePolicy], so the toolbar entry and the status card keep the previous verdict
     * during an automatic re-check instead of flashing "checking" on every return to the
     * foreground.
     */

    /**
     * Seeds the displayed verdict and the stream liveness from the runtime snapshot, so a fresh UI
     * process shows the previous answer instead of "尚未检测" while the probe runs.
     */

    /** Reads the runtime's cached view once per service connection, before any probe is owed. */

    /**
     * Records the owed check and re-reads the saved address before running it. The follow-up probe
     * is automatic: a config save already reset the displayed verdict, so it needs no "checking"
     * state.
     */

    override fun refreshConnectionSummary() {
        performRefreshConnectionSummary()
    }

    override fun notificationSettings(
        value: NotificationSettings?,
        onFailure: () -> Unit,
        onComplete: (NotificationSettings) -> Unit,
    ) {
        performNotificationSettings(value, onFailure, onComplete)
    }

    override fun quietHours(
        value: QuietHours?,
        onFailure: () -> Unit,
        onComplete: (QuietHours) -> Unit,
    ) {
        performQuietHours(value, onFailure, onComplete)
    }

    override fun bindDevice(request: BindingRequest, onBound: () -> Unit) {
        performBindDevice(request, onBound)
    }

    override fun rotateCredentials(payload: String, onRotated: () -> Unit) {
        performRotateCredentials(payload, onRotated)
    }

    override fun copyPushKey(onReady: (String) -> Unit) {
        performCopyPushKey(onReady)
    }

    override fun readMessages(
        injectTest: Boolean,
        delete: IncomingMessage?,
        search: String,
        serverId: String,
        readFilter: MessageReadFilter,
        showStatus: Boolean,
    ) {
        performReadMessages(injectTest, delete, search, serverId, readFilter, showStatus)
    }

    override fun nextMessages() {
        performNextMessages()
    }

    override fun previousMessages() {
        performPreviousMessages()
    }

    override fun remindMessage(key: MessageKey) {
        performRemindMessage(key)
    }

    override fun closeDetail() {
        performCloseDetail()
    }

    override fun loadDetail(message: IncomingMessage, onLoaded: ((IncomingMessage) -> Unit)?) {
        performLoadDetail(message, onLoaded)
    }

    override fun openMessage(key: MessageKey, onComplete: () -> Unit) {
        performOpenMessage(key, onComplete)
    }

    override fun manageMessages(
        action: MessageAction,
        keys: List<MessageKey>,
        preservePage: Boolean,
        onComplete: (() -> Unit)?,
    ) {
        performManageMessages(action, keys, preservePage, onComplete)
    }

    override fun cleanupSettings(
        value: io.github.beacon.app.domain.model.CleanupSettings?,
        onFailure: () -> Unit,
        onComplete: (io.github.beacon.app.domain.model.CleanupSettings) -> Unit,
    ) {
        performCleanupSettings(value, onFailure, onComplete)
    }

    override fun previewCleanup(policy: CleanupPolicy) {
        performPreviewCleanup(policy)
    }

    override fun pendingReminders(onFailure: () -> Unit, onReady: (Long) -> Unit) {
        performPendingReminders(onFailure, onReady)
    }

    override fun checkpointStorage() {
        performCheckpointStorage()
    }

    override fun markAllRead() {
        performMarkAllRead()
    }

    override fun clearAllMessages() {
        performClearAllMessages()
    }

    override fun applyCleanup(token: String) {
        performApplyCleanup(token)
    }

    internal fun failTransport(message: String, kind: OperationKind = OperationKind.RESULT) {
        mutableState.update {
            it.copy(transportBusy = false, status = OperationStatus(kind, message))
        }
    }

    override fun transport(
        settings: TransportSettings?,
        notifyOnly: Boolean,
        onConfigured: (() -> Unit)?,
    ) {
        performTransport(settings, notifyOnly, onConfigured)
    }

    override fun refreshPolling(onComplete: ((String) -> Unit)?) {
        performRefreshPolling(onComplete)
    }

    override fun setPolling(config: PollingConfig) {
        performSetPolling(config)
    }

    override fun saveReception(plan: ReceivePlan, polling: PollingConfig) {
        performSaveReception(plan, polling)
    }

    override fun receivePlan(value: ReceivePlan?) {
        performReceivePlan(value)
    }

    internal fun reconcile(binder: android.os.IBinder, text: String): String =
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
        performScheduleOneMinuteTest()
    }
}
