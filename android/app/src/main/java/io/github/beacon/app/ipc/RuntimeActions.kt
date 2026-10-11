package io.github.beacon.app.ipc

import android.content.Context
import io.github.beacon.app.domain.model.BindingRequest
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.domain.model.OperationStatus
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.QuietHours
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.domain.model.TransportSettings
import kotlinx.coroutines.flow.StateFlow

interface RuntimeActions {
    val state: StateFlow<RuntimeState>

    fun start(context: Context)

    fun stop(status: OperationStatus = OperationStatus.connectionLost("已解除接收服务诊断绑定"))

    fun checkServer() {}

    /**
     * Probes the saved server after returning to the foreground. Unlike [checkServer] it never
     * takes over the displayed result: the last answer stays on screen and is only replaced by the
     * new one.
     */
    fun autoCheckServer() {}

    fun barkEncryption(key: String, iv: String, onComplete: (String) -> Unit) {}

    fun testDelivery(onComplete: (String) -> Unit) {}

    fun historyArchive(
        uri: String,
        password: String,
        importing: Boolean,
        encrypted: Boolean,
        onComplete: (String) -> Unit,
    ) {}

    fun settingsBackup(
        payload: String? = null,
        onFailure: () -> Unit = {},
        onComplete: (String) -> Unit,
    ) {}

    fun eventLog(
        onFailure: () -> Unit = {},
        onComplete: (List<io.github.beacon.app.domain.model.RuntimeEvent>) -> Unit,
    ) {}

    fun clearEventLog(onFailure: () -> Unit = {}, onComplete: () -> Unit) {}

    fun reconnectRealtime() {}

    fun clearConnection(onCleared: () -> Unit = {}) {}

    fun revokeDevice(onRevoked: () -> Unit = {}) {}

    fun bindDevice(request: BindingRequest, onBound: () -> Unit) {}

    fun rotateCredentials(payload: String, onRotated: () -> Unit) {}

    fun copyPushKey(onReady: (String) -> Unit) {}

    fun refreshConnectionSummary()

    fun readMessages(
        injectTest: Boolean = false,
        delete: IncomingMessage? = null,
        search: String = state.value.messageQuery.search,
        serverId: String = state.value.messageQuery.serverId,
        readFilter: MessageReadFilter = state.value.messageQuery.readFilter,
        showStatus: Boolean = true,
    )

    fun nextMessages()

    fun previousMessages()

    fun closeDetail()

    fun loadDetail(message: IncomingMessage, onLoaded: ((IncomingMessage) -> Unit)? = null)

    fun openMessage(key: MessageKey, onComplete: () -> Unit = {}) {}

    fun remindMessage(key: MessageKey) {}

    fun manageMessages(
        action: MessageAction,
        keys: List<MessageKey>,
        preservePage: Boolean = false,
        onComplete: (() -> Unit)? = null,
    )

    fun previewCleanup(policy: io.github.beacon.app.domain.model.CleanupPolicy) {}

    fun applyCleanup(token: String) {}

    fun clearAllMessages() {}

    fun pendingReminders(onReady: (Long) -> Unit) {}

    fun cleanupSettings(
        value: io.github.beacon.app.domain.model.CleanupSettings? = null,
        onFailure: () -> Unit = {},
        onComplete: (io.github.beacon.app.domain.model.CleanupSettings) -> Unit,
    ) {}

    fun transport(
        settings: TransportSettings? = null,
        notifyOnly: Boolean = false,
        onConfigured: (() -> Unit)? = null,
    )

    fun refreshPolling(onComplete: ((String) -> Unit)? = null)

    fun notificationSettings(
        value: NotificationSettings? = null,
        onFailure: () -> Unit = {},
        onComplete: (NotificationSettings) -> Unit,
    ) {}

    fun quietHours(
        value: QuietHours? = null,
        onFailure: () -> Unit = {},
        onComplete: (QuietHours) -> Unit,
    ) {}

    fun receivePlan(value: io.github.beacon.app.domain.model.ReceivePlan? = null) {}

    fun setPolling(config: PollingConfig)

    fun scheduleOneMinuteTest()
}
