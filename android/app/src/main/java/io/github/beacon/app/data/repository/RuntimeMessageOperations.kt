package io.github.beacon.app.data.repository

import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.OperationFailure
import io.github.beacon.app.ipc.RuntimeMessageClient
import io.github.beacon.app.ipc.request
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** message use cases; repository remains the lifecycle/state owner. */
internal fun RuntimeRepository.performReadMessages(
    injectTest: Boolean,
    delete: IncomingMessage?,
    search: String,
    serverId: String,
    readFilter: MessageReadFilter,
    showStatus: Boolean,
) {
    if (!state.value.messageBusy) {
        preserveReadingWindow = false
        newMessageAnchor = null
    }
    loadPage(
        MessageQuery(search, serverId, readFilter = readFilter),
        listOf(Long.MAX_VALUE),
        0,
        injectTest,
        delete,
        showStatus,
    )
}

internal fun RuntimeRepository.performNextMessages() {
    val current = state.value
    val cursor = current.nextMessageCursor ?: return
    loadPage(
        current.messageQuery.copy(before = cursor),
        pageHistory.take(current.messagePageIndex + 1) + cursor,
        current.messagePageIndex + 1,
        showStatus = false,
    )
}

internal fun RuntimeRepository.performPreviousMessages() {
    val current = state.value
    if (current.messagePageIndex <= 0) return
    val index = current.messagePageIndex - 1
    loadPage(current.messageQuery.copy(before = pageHistory[index]), pageHistory, index)
}

internal fun RuntimeRepository.loadPage(
    query: MessageQuery,
    history: List<Long>,
    index: Int,
    injectTest: Boolean = false,
    delete: IncomingMessage? = null,
    showStatus: Boolean = true,
    deliveryRevision: Long? = null,
    retainWindow: Boolean = false,
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
            if (!retainWindow) pageHistory = history
            if (!retainWindow && index==0)newMessageAnchor=null
            mutableState.update {
                it.copy(
                    messages =
                        if (retainWindow) it.messages
                        else if (
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
                    newMessageCount = if(retainWindow)result.page.newerCount else if(index>it.messagePageIndex)it.newMessageCount else 0L,
                    nextMessageCursor = if (retainWindow) it.nextMessageCursor else result.page.nextBefore,
                    messageQuery = query.copy(before = Long.MAX_VALUE, anchor = null),
                    messagePageIndex = if (retainWindow) it.messagePageIndex else index,
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

internal fun RuntimeRepository.performRemindMessage(key: MessageKey) {
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

internal fun RuntimeRepository.performCloseDetail() {
    detailGeneration++
    mutableState.update { it.copy(selectedMessage = null) }
}

internal fun RuntimeRepository.performLoadDetail(message: IncomingMessage, onLoaded: ((IncomingMessage) -> Unit)?) {
    readDetail(MessageKey(message.server, message.id), onLoaded)
}

internal fun RuntimeRepository.performOpenMessage(key: MessageKey, onComplete: () -> Unit) {
    key.validate()
    readDetail(key, null, onComplete)
}

internal fun RuntimeRepository.readDetail(
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
                        manage(
                            MessageMutation(
                                MessageAction.MARK_READ,
                                listOf(MessageKey(full.server, full.id)),
                            ),
                            preservePage = true,
                            onComplete = null,
                            showStatus = false,
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

internal fun RuntimeRepository.performManageMessages(
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

internal fun RuntimeRepository.manage(
    request: MessageMutation,
    preservePage: Boolean,
    onComplete: (() -> Unit)?,
    showStatus: Boolean = true,
) {
    if (state.value.messageBusy || !ready()) return
    val query = state.value.messageQuery
    val affectedKeys = request.keys.toSet()
    mutableState.update {
        it.copy(dataBusy = true, status = OperationStatus.pending("正在处理消息管理操作"))
    }
    connection.execute(
        { binder ->
            val client = RuntimeMessageClient(binder)
            val result = client.mutate(request)
            result to if (request.action == MessageAction.DELETE)
                runCatching { client.query(query).page }.getOrNull()
            else null
        },
        { (result, refreshedPage) ->
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
                    status =
                        if (showStatus) OperationStatus.result(text)
                        else OperationStatus.diagnostic(""),
                )
            }
            onComplete?.invoke()
            if (request.action == MessageAction.DELETE) {
                mutableState.update { current ->
                    io.github.beacon.app.domain.policy.MessageWindowPolicy.afterDeletion(
                        current,
                        affectedKeys,
                        result.changed,
                        refreshedPage?.total,
                        refreshedPage?.unread,
                    )
                }
                if (state.value.messages.isEmpty()) {
                    if (state.value.nextMessageCursor != null) nextMessages()
                    else if (state.value.messageTotal > 0)
                        readMessages(
                            search = query.search,
                            serverId = query.serverId,
                            readFilter = query.readFilter,
                            showStatus = false,
                        )
                }
            } else if (preservePage) {
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

internal fun RuntimeRepository.failData(
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
