package io.github.beacon.app.runtime

import android.os.Parcel
import android.os.Process
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.core.currentProcessName
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.receive.ReceivePipeline
import io.github.beacon.app.ipc.RuntimeProtocol.DELETE
import io.github.beacon.app.ipc.RuntimeProtocol.INJECT_TEST
import io.github.beacon.app.ipc.RuntimeProtocol.PING
import io.github.beacon.app.ipc.RuntimeProtocol.RECENT

/** IPC payload handling; invoked only after the service validates caller UID and descriptor. */
internal fun RuntimeProbeService.handleLegacyCommands(code: Int, data: Parcel, reply: Parcel?): Boolean {
    if (code !in listOf(PING, RECENT, INJECT_TEST, DELETE)) return false
    if (code != PING) {
        val server = if (code == DELETE) data.readString() else null
        val id = if (code == DELETE) data.readString() else null
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        if (code == DELETE)
            require(
                !server.isNullOrBlank() &&
                    server.length <= 128 &&
                    !id.isNullOrBlank() &&
                    id.length <= 128
            )
        if (code == INJECT_TEST && !BuildConfig.DEBUG)
            throw SecurityException("Debug only")
        val rows =
            try {
                worker
                    .submit<List<IncomingMessage>> {
                        if (code == INJECT_TEST) {
                            // Fixed identity permits repeat-delivery and tombstone
                            // verification.
                            ReceivePipeline(store) { _, _ ->
                                    throw java.io.IOException(
                                        "No server ACK transport configured"
                                    )
                                }
                                .accept(
                                    IncomingMessage(
                                        "diagnostic-local",
                                        "pipeline-test-${BuildConfig.VERSION_CODE}",
                                        "本地流水线测试",
                                        "仅用于验证保存、去重与删除墓碑；不是服务器消息。",
                                    )
                                )
                            realtime.messagesChanged()
                        }
                        if (code == DELETE)
                            store.remove(requireNotNull(server), requireNotNull(id))
                        store.recent()
                    }
                    .get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw IllegalStateException("Data operation failed", e)
            }
        requireNotNull(reply).apply {
            writeNoException()
            writeInt(rows.size)
            rows.forEach {
                writeString(it.server)
                writeString(it.id)
                writeString(it.title)
                writeString(it.body)
            }
        }
        return true
    }
    if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
    requireNotNull(reply).apply {
        writeNoException()
        writeInt(Process.myPid())
        writeInt(BuildConfig.VERSION_CODE)
        writeString(currentProcessName())
    }
    return true}
