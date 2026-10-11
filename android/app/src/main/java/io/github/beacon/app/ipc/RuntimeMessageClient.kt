package io.github.beacon.app.ipc

import android.os.IBinder
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.model.IncomingMessage

internal data class RuntimeMessagePage(val page: MessagePage)

/** Typed history IPC. UI and repositories never encode or decode parcels. */
internal class RuntimeMessageClient(private val binder: IBinder) {
    fun clearAllMessages(): Long =
        binder.request(RuntimeProtocol.CLEAR_ALL_MESSAGES) {
            it.readLong().also { value -> require(value >= 0) }
        }

    fun pendingReminderCount(): Long =
        binder.request(RuntimeProtocol.PENDING_REMINDERS) {
            it.readLong().also { value -> require(value >= 0) }
        }

    fun cleanupSettings(value: CleanupSettings?): CleanupSettings =
        binder.request(
            RuntimeProtocol.CLEANUP_SETTINGS,
            write = {
                it.writeInt(if (value == null) 0 else 1)
                if (value != null) {
                    value.policy.validate()
                    it.writeInt(value.policy.days)
                    it.writeInt(value.policy.maxMessages)
                    it.writeInt(if (value.policy.keepUnread) 1 else 0)
                    it.writeInt(if (value.automatic) 1 else 0)
                }
            },
        ) {
            val policy =
                CleanupPolicy(
                    it.readInt(),
                    it.readInt(),
                    it.readInt().also { require(it in 0..1) } == 1,
                )
            policy.validate()
            CleanupSettings(
                policy,
                it.readInt().also { require(it in 0..1) } == 1,
                requireNotNull(it.readString()),
            )
        }

    fun previewCleanup(policy: CleanupPolicy): CleanupPreview =
        binder.request(
            RuntimeProtocol.PREVIEW_CLEANUP,
            write = {
                it.writeInt(policy.days)
                it.writeInt(policy.maxMessages)
                it.writeInt(if (policy.keepUnread) 1 else 0)
            },
        ) {
            val base =
                CleanupPreview(
                    requireNotNull(it.readString()),
                    it.readLong(),
                    it.readLong(),
                    it.readLong(),
                    it.readLong(),
                    it.readLong(),
                    it.readLong(),
                    it.readInt(),
                    it.readLong(),
                )
            val preview =
                if (it.dataAvail() == 0) base
                else {
                    check(it.dataAvail() == 5 * java.lang.Long.BYTES) { "Invalid storage snapshot" }
                    base.copy(
                        pendingNotifications = it.readLong(),
                        tombstones = it.readLong(),
                        walBytes = it.readLong(),
                        availableBytes = it.readLong(),
                        sampledAtMs = it.readLong(),
                    )
                }
            check(
                preview.token.length in 1..64 &&
                    preview.total >= 0 &&
                    preview.unread in 0..preview.total &&
                    preview.pending in 0..preview.total &&
                    preview.databaseBytes >= 0 &&
                    preview.reusableBytes in 0..preview.databaseBytes &&
                    preview.pendingNotifications in 0..preview.total &&
                    preview.tombstones >= 0 &&
                    preview.walBytes >= 0 &&
                    preview.availableBytes >= -1 &&
                    preview.sampledAtMs >= 0 &&
                    preview.eligible in 0..preview.total &&
                    preview.protected in 0..preview.total &&
                    preview.planned in 0..CleanupPolicy.BATCH_SIZE
            )
            preview
        }

    fun applyCleanup(token: String): MessageMutationResult =
        binder.request(RuntimeProtocol.APPLY_CLEANUP, write = { it.writeString(token) }) {
            val changed = it.readInt()
            check(changed in 0..CleanupPolicy.BATCH_SIZE)
            MessageMutationResult(changed, it.readUndo())
        }

    fun query(query: MessageQuery): RuntimeMessagePage =
        binder.request(
            RuntimeProtocol.QUERY,
            write = {
                it.writeString(query.search)
                it.writeString(query.serverId)
                it.writeLong(query.before)
                it.writeInt(query.readFilter.wireId)
            },
        ) { reply ->
            val total = reply.readLong()
            val unread = reply.readLong()
            val next = reply.readLong()
            val count = reply.readInt()
            check(total >= 0 && unread in 0..total && count in 0..MessageQuery.PAGE_SIZE)
            check(next == -1L || next in 1 until query.before)
            val messages = List(count) { reply.readMessage() }
            RuntimeMessagePage(
                MessagePage(messages, total, next.takeIf { it > 0 }, unread, reply.readUndo())
            )
        }

    fun injectOrDelete(message: IncomingMessage?) {
        binder.request(
            if (message == null) RuntimeProtocol.INJECT_TEST else RuntimeProtocol.DELETE,
            write = {
                if (message != null) {
                    it.writeString(message.server)
                    it.writeString(message.id)
                }
            },
        ) { reply ->
            val count = reply.readInt()
            check(count in 0..20)
            repeat(count) { repeat(4) { requireNotNull(reply.readString()) } }
        }
    }

    fun detail(message: IncomingMessage): IncomingMessage? =
        detail(MessageKey(message.server, message.id))

    fun detail(key: MessageKey): IncomingMessage? =
        binder.request(
            RuntimeProtocol.DETAIL,
            write = {
                key.validate()
                it.writeString(key.server)
                it.writeString(key.id)
            },
        ) {
            if (it.readFlag()) it.readMessage() else null
        }

    fun remind(key: MessageKey): String =
        binder.request(
            RuntimeProtocol.REMIND_MESSAGE,
            write = {
                key.validate()
                it.writeString(key.server)
                it.writeString(key.id)
            },
        ) {
            requireNotNull(it.readString()).also { text -> require(text.length <= 2048) }
        }

    fun mutate(request: MessageMutation): MessageMutationResult =
        binder.request(
            RuntimeProtocol.MUTATE,
            write = {
                run {
                    it.writeInt(request.action.wireId)
                    it.writeInt(request.keys.size)
                    request.keys.forEach { key ->
                        it.writeString(key.server)
                        it.writeString(key.id)
                    }
                }
            },
        ) {
            val changed = it.readInt()
            check(changed in 0..100)
            MessageMutationResult(changed, it.readUndo())
        }
}
