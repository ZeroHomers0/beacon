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

    fun ackCompleted(server: String, id: String)
}

data class ReceiveResult(val saved: SaveResult, val ackPending: Boolean)

interface SyncStore : ReceiveStore {
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
        require(message.bark.toByteArray(Charsets.UTF_8).size <= 100000)
        require(message.priority in 0..10)
        require(message.server.isNotBlank() && message.server.length <= MessageLimits.ID_CHARACTERS)
        require(message.id.isNotBlank() && message.id.length <= MessageLimits.ID_CHARACTERS)
        require(message.title.toByteArray(Charsets.UTF_8).size <= MessageLimits.TITLE_BYTES)
        require(message.subtitle.toByteArray(Charsets.UTF_8).size <= MessageLimits.TITLE_BYTES)
        require(message.body.toByteArray(Charsets.UTF_8).size <= MessageLimits.BODY_BYTES)
    }
}
