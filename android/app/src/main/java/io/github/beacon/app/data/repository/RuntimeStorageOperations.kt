package io.github.beacon.app.data.repository

import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.OperationFailure
import io.github.beacon.app.ipc.RuntimeControlClient
import io.github.beacon.app.ipc.RuntimeMessageClient
import kotlinx.coroutines.flow.update

/** storage use cases; repository remains the lifecycle/state owner. */
internal fun RuntimeRepository.performHistoryArchive(
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

internal fun RuntimeRepository.performSettingsBackup(
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

internal fun RuntimeRepository.performCleanupSettings(
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

internal fun RuntimeRepository.performPreviewCleanup(policy: CleanupPolicy) {
    if (state.value.messageBusy || !ready()) return
    policy.validate()
    mutableState.update { it.copy(dataBusy = true, cleanupPreviewFresh = false) }
    connection.execute(
        { RuntimeMessageClient(it).previewCleanup(policy) },
        { preview ->
            mutableState.update {
                it.copy(dataBusy = false, cleanupPreview = preview, cleanupPolicy = policy, cleanupPreviewFresh = true)
            }
        },
        { error -> failData("清理预览失败：${OperationFailure.describe(error,crossProcess=true)}") },
    )
}

internal fun RuntimeRepository.performPendingReminders(onFailure: () -> Unit, onReady: (Long) -> Unit) {
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

internal fun RuntimeRepository.performCheckpointStorage() {
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

internal fun RuntimeRepository.performMarkAllRead() {
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

internal fun RuntimeRepository.performClearAllMessages() {
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

internal fun RuntimeRepository.performApplyCleanup(token: String) {
    if (state.value.messageBusy || !ready()) return
    if (!state.value.cleanupPreviewFresh || state.value.cleanupPreview?.token != token) return
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
