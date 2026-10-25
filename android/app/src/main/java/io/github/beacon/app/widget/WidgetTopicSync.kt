package io.github.beacon.app.widget

import android.content.Context
import io.github.beacon.app.domain.model.OperationKind
import io.github.beacon.app.ipc.RuntimeConnection
import io.github.beacon.app.ipc.RuntimeControlClient
import io.github.beacon.app.runtime.RuntimeProbeService

/** One catalog request per visible editor entry, using receive credentials only in :runtime. */
internal class WidgetTopicSync(context: Context, private val onResult: (String) -> Unit) : AutoCloseable {
    private var requested = false
    private var closed = false
    private val connection = RuntimeConnection(
        RuntimeProbeService::class.java,
        onStatus = { status, _ ->
            if (!closed && status.kind == OperationKind.CONNECTION_LOST) onResult("无法同步主题，请重试；本地选项保留。")
        },
        onConnected = { request() },
    )
    private val app = context.applicationContext
    fun start() = connection.start(app)
    private fun request() {
        if (requested) return
        requested = true
        connection.execute(
            operation = { RuntimeControlClient(it).syncWidgetTopics() },
            onResult = { if(!closed)onResult(it) },
            onError = { if(!closed)onResult("主题同步未完成，请重试；本地选项保留。") },
        )
    }
    override fun close() { closed = true; connection.close() }
}
