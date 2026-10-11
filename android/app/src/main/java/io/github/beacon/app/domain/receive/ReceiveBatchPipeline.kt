package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.IncomingMessage
import java.io.IOException

/**
 * A batch is confirmed only after every member has a durable message/deletion decision and receipt.
 */
class ReceiveBatchPipeline(
    private val store: ReceiveStore,
    private val acknowledge: (String, List<String>) -> Unit,
    private val shouldContinue: () -> Boolean = { true },
) {
    fun accept(messages: List<IncomingMessage>): Int {
        require(messages.size <= MAX_BATCH_SIZE)
        if (messages.isEmpty()) return 0
        val server = messages.first().server
        require(messages.all { it.server == server })
        require(messages.map { it.id }.toSet().size == messages.size)
        messages.forEach(ReceiveMessageValidation::validate)
        var inserted = 0
        for (message in messages) {
            if (!shouldContinue()) throw SyncCancelledException()
            if (store.save(message) == SaveResult.INSERTED) inserted++
        }
        if (!shouldContinue()) throw SyncCancelledException()
        val ids = messages.map { it.id }
        try {
            acknowledge(server, ids)
            ids.forEach { store.ackCompleted(server, it) }
        } catch (error: IOException) {
            if (error is HttpRejectedException) throw error
            // Durable receipts remain for a later request. Authentication/protocol errors
            // propagate.
        }
        return inserted
    }

    companion object {
        const val MAX_BATCH_SIZE = 100
    }
}
