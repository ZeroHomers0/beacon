package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.IncomingMessage
import org.junit.Assert.*
import org.junit.Test

class ReceivePipelineTest {
    private class Store : ReceiveStore {
        val rows = mutableMapOf<Pair<String, String>, IncomingMessage>()
        val pending = mutableSetOf<Pair<String, String>>()
        val deleted = mutableSetOf<Pair<String, String>>()
        var fail = false

        override fun save(message: IncomingMessage): SaveResult {
            if (fail) throw java.io.IOException("disk unavailable")
            val key = message.server to message.id
            val result =
                when {
                    key in deleted -> SaveResult.DELETED
                    key in rows -> SaveResult.DUPLICATE
                    else -> {
                        rows[key] = message
                        SaveResult.INSERTED
                    }
                }
            pending.add(key)
            return result
        }

        override fun ackCompleted(server: String, id: String) {
            pending.remove(server to id)
        }
    }

    private val message = IncomingMessage("server-a", "id-1", "Title", "Body")

    @Test
    fun neverAcknowledgesStorageFailure() {
        val store = Store().apply { fail = true }
        var acks = 0
        assertThrows(java.io.IOException::class.java) {
            ReceivePipeline(store) { _, _ -> acks++ }.accept(message)
        }
        assertEquals(0, acks)
    }

    @Test
    fun ackFollowsDurableMessageAndIntent() {
        val store = Store()
        val result =
            ReceivePipeline(store) { server, id ->
                    assertTrue(store.rows.containsKey(server to id))
                    assertTrue(store.pending.contains(server to id))
                }
                .accept(message)
        assertEquals(SaveResult.INSERTED, result.saved)
        assertFalse(result.ackPending)
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun retryDoesNotOverwriteOrDuplicateAndKeepsAck() {
        val store = Store()
        val failedTransport =
            ReceivePipeline(store) { _, _ -> throw java.io.IOException("offline") }
        assertTrue(failedTransport.accept(message).ackPending)
        assertEquals(
            SaveResult.DUPLICATE,
            failedTransport.accept(message.copy(body = "changed")).saved,
        )
        assertEquals(message.body, store.rows.values.single().body)
        assertEquals(1, store.pending.size)
        assertFalse(ReceivePipeline(store) { _, _ -> }.accept(message).ackPending)
    }

    @Test
    fun deletedMessageDoesNotReappearOnRedelivery() {
        val store = Store().apply { deleted.add(message.server to message.id) }
        assertEquals(SaveResult.DELETED, ReceivePipeline(store) { _, _ -> }.accept(message).saved)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun identicalIdsFromDifferentServersAreIndependent() {
        val store = Store()
        val pipeline = ReceivePipeline(store) { _, _ -> }
        pipeline.accept(message)
        pipeline.accept(message.copy(server = "server-b"))
        assertEquals(2, store.rows.size)
    }

    @Test
    fun invalidMessageNeverReachesStorageOrAck() {
        val store = Store()
        val pipeline = ReceivePipeline(store) { _, _ -> fail("unexpected ACK") }
        assertThrows(IllegalArgumentException::class.java) {
            pipeline.accept(message.copy(id = ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            pipeline.accept(message.copy(body = "中".repeat(23000)))
        }
        assertTrue(store.rows.isEmpty())
    }
}
