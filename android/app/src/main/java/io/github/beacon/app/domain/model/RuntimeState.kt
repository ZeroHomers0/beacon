package io.github.beacon.app.domain.model

data class RuntimeState(
    val configuredEndpoint: String = "",
    val serverChecking: Boolean = false,
    val serverReachable: Boolean? = null,
    val serverCheckResult: String = "尚未检测",
    val serverCheckedAtMs: Long = 0L,
    val connectionSummaryLoaded: Boolean = false,
    val status: OperationStatus = OperationStatus.connectionLost("接收服务尚未验证"),
    val messages: List<IncomingMessage> = emptyList(),
    val messageTotal: Long = 0L,
    val messageUnread: Long = 0L,
    val messageQuery: MessageQuery = MessageQuery(),
    val messagePageIndex: Int = 0,
    val dataBusy: Boolean = false,
    val messageLoadFailed: Boolean = false,
    val selectedMessage: IncomingMessage? = null,
    val nextMessageCursor: Long? = null,
    val transportBusy: Boolean = false,
    val pollingState: PollingState = PollingState(),
    val receivePlan: ReceivePlan = ReceivePlan(),
    val receivePlanLoaded: Boolean = false,
    val realtimeStatus: String = "即时接收未启动",
    val realtimeRunning: Boolean = false,
    val realtimeDiagnostics: RealtimeDiagnostics = RealtimeDiagnostics(),
    val recovery: RecoverySnapshot = RecoverySnapshot(),
    val deliveryRevision: Long = 0,
    val lastManualOutcome: SyncOutcome? = null,
    val runtimeConnected: Boolean = false,
    val cleanupPreview: CleanupPreview? = null,
    val cleanupPolicy: CleanupPolicy? = null,
) {
    val messageBusy: Boolean
        get() = dataBusy || transportBusy
}
