package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageLimits

enum class SaveResult {
    INSERTED,
    DUPLICATE,
    DELETED,
}

interface ReceiveStore {
    /** Must atomically save the message (or deletion decision) and pending ACK. */
    fun save(message: IncomingMessage): SaveResult

    /** Implementations may commit a bounded page atomically before any server ACK. */
    fun saveBatch(messages: List<IncomingMessage>, shouldContinue: () -> Boolean): Int {
        var inserted = 0
        for (message in messages) {
            if (!shouldContinue()) throw SyncCancelledException()
            if (save(message) == SaveResult.INSERTED) inserted++
        }
        return inserted
    }

    fun ackCompleted(server: String, id: String)
}

data class ReceiveResult(val saved: SaveResult, val ackPending: Boolean)

interface SyncStore : ReceiveStore {
    fun ackBatchCompleted(server: String, ids: List<String>) {
        ids.forEach { ackCompleted(server, it) }
    }

    fun retireExpiredRecords(server: String, beforeMs: Long): Int = 0

    fun requiresRetentionContract(server: String): Boolean = false

    fun pendingIds(server: String): List<String>

    fun cursor(server: String): Long

    fun advanceCursor(server: String, sequence: Long)
}

/** Storage failure propagates. Transport failure leaves the durable ACK pending. */
class ReceivePipeline(
    private val store: ReceiveStore,
    private val acknowledge: (String, String) -> Unit,
) {
    fun accept(message: IncomingMessage): ReceiveResult {
        ReceiveMessageValidation.validate(message)
        val saved = store.save(message)
        return try {
            acknowledge(message.server, message.id)
            store.ackCompleted(message.server, message.id)
            ReceiveResult(saved, false)
        } catch (e: java.io.IOException) {
            if (e is HttpRejectedException) throw e
            ReceiveResult(saved, true)
        }
    }
}

/** Identical byte limits for immediate delivery and paged HTTP persistence. */
object ReceiveMessageValidation {
    fun validate(message: IncomingMessage) {
        io.github.beacon.app.data.remote.MessageActionsCodec.validate(message.actions)
        require(MessageLimits.textFits(message.applicationName, MessageLimits.NAME_BYTES))
        require(
            message.applicationId.isEmpty() || message.applicationId.matches(Regex("[0-9a-f]{64}"))
        )
        require(MessageLimits.textFits(message.group, MessageLimits.NAME_BYTES) && message.expiresAtMs >= 0)
        require(MessageLimits.textFits(message.bark, MessageLimits.BARK_BYTES))
        require(message.priority in 0..10)
        require(message.server.isNotBlank() && message.server.length <= MessageLimits.ID_CHARACTERS)
        require(message.id.isNotBlank() && message.id.length <= MessageLimits.ID_CHARACTERS)
        require(MessageLimits.textFits(message.title, MessageLimits.TITLE_BYTES))
        require(MessageLimits.textFits(message.subtitle, MessageLimits.TITLE_BYTES))
        require(message.body.isNotEmpty() && MessageLimits.textFits(message.body, MessageLimits.BODY_BYTES))
    }
}
