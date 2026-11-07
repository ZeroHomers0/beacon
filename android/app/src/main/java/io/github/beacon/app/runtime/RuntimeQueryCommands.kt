package io.github.beacon.app.runtime

import android.os.Parcel
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageMutation
import io.github.beacon.app.domain.model.MessageMutationResult
import io.github.beacon.app.domain.model.MessagePage
import io.github.beacon.app.domain.model.MessageQuery
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.ipc.RuntimeProtocol.DETAIL
import io.github.beacon.app.ipc.RuntimeProtocol.MUTATE
import io.github.beacon.app.ipc.RuntimeProtocol.QUERY
import io.github.beacon.app.ipc.RuntimeProtocol.UNDO_DELETE

/** IPC payload handling; invoked only after the service validates caller UID and descriptor. */
internal fun RuntimeProbeService.handleQueryCommands(code: Int, data: Parcel, reply: Parcel?): Boolean {
    if (code == MUTATE || code == UNDO_DELETE) {
        val requestedAt = System.currentTimeMillis()
        val request =
            if (code == MUTATE) {
                val action =
                    MessageAction.fromWireId(data.readInt())
                        ?: throw IllegalArgumentException("Invalid action")
                val count = data.readInt()
                require(count in 1..100)
                MessageMutation(
                        action,
                        List(count) {
                            MessageKey(
                                requireNotNull(data.readString()),
                                requireNotNull(data.readString()),
                            )
                        },
                    )
                    .also { it.validate() }
            } else null
        val token =
            if (code == UNDO_DELETE)
                requireNotNull(data.readString()).also { require(it.length in 1..64) }
            else null
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val result =
            try {
                worker
                    .submit<MessageMutationResult> {
                        if (request != null) store.mutate(request)
                        else
                            MessageMutationResult(
                                0 // Retired transaction: never restore deleted
                                // messages.
                            )
                    }
                    .get(45, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw IllegalStateException("Message management failed", e)
            }
        requireNotNull(reply).apply {
            writeNoException()
            writeInt(result.changed)
            writeString(result.undo?.token ?: "")
            writeLong(result.undo?.expiresAtMs ?: 0)
            writeInt(result.undo?.count ?: 0)
        }
        return true
    }
    if (code == QUERY || code == DETAIL) {
        val query =
            if (code == QUERY) {
                val search = requireNotNull(data.readString())
                val serverId = requireNotNull(data.readString())
                val before = data.readLong()
                val filter =
                    MessageReadFilter.fromWireId(data.readInt())
                        ?: throw IllegalArgumentException("Invalid read filter")
                val anchorServer = if (data.dataAvail() > 0) data.readString() else null
                val anchorId = if (data.dataAvail() > 0) data.readString() else null
                require((anchorServer == null) == (anchorId == null))
                val anchor = anchorServer?.let {
                    MessageKey(it, requireNotNull(anchorId))
                }
                MessageQuery(search, serverId, before, filter, anchor).also {
                    it.validate()
                }
            } else null
        val server = if (code == DETAIL) requireNotNull(data.readString()) else null
        val id = if (code == DETAIL) requireNotNull(data.readString()) else null
        if (code == DETAIL)
            require(
                !server.isNullOrBlank() &&
                    server.length <= 128 &&
                    !id.isNullOrBlank() &&
                    id.length <= 128
            )
        if (data.dataAvail() != 0) throw SecurityException("Unexpected payload")
        val result =
            try {
                worker
                    .submit<Any?> {
                        if (query != null) store.page(query)
                        else store.detail(requireNotNull(server), requireNotNull(id))
                    }
                    .get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw IllegalStateException("History operation failed", e)
            }
        requireNotNull(reply).apply {
            writeNoException()
            if (query != null) {
                val page = result as MessagePage
                writeLong(page.total)
                writeLong(page.unread)
                writeLong(page.nextBefore ?: -1)
                writeInt(page.items.size)
                page.items.forEach { writeMessage(this, it) }
                writeString(page.undo?.token ?: "")
                writeLong(page.undo?.expiresAtMs ?: 0)
                writeInt(page.undo?.count ?: 0)
                writeLong(page.newerCount)
            } else {
                val message = result as IncomingMessage?
                writeInt(if (message == null) 0 else 1)
                if (message != null) writeMessage(this, message)
            }
        }
        return true
    }

    return false
}
