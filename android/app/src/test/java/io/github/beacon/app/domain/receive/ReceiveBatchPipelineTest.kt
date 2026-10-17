package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.IncomingMessage
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ReceiveBatchPipelineTest {
    @Test
    fun overloadedServerStopsBatchWithoutRetiringDurableReceipts() {
        val store = Store()
        assertThrows(HttpTransientException::class.java) {
            ReceiveBatchPipeline(
                    store,
                    { _, _ ->
                        throw HttpTransientException(503, SyncStage.ACK, 60_000)
                    },
                )
                .accept(messages)
        }
        assertEquals(3, store.saved.size)
        assertEquals(store.saved, store.pending)
    }

    private class Store : ReceiveStore {
        val saved = mutableSetOf<String>()
        val pending = mutableSetOf<String>()
        var failAt = ""

        override fun save(message: IncomingMessage): SaveResult {
            if (message.id == failAt) throw IOException("synthetic storage failure")
            pending.add(message.id)
            return if (saved.add(message.id)) SaveResult.INSERTED else SaveResult.DUPLICATE
        }

        override fun ackCompleted(server: String, id: String) {
            pending.remove(id)
        }
    }

    private val messages = (1..3).map { IncomingMessage("server", "id-$it", "title", "body") }

    @Test
    fun batchIsConfirmedOnlyAfterEveryMemberIsDurable() {
        val store = Store()
        var requests = 0
        val pipeline =
            ReceiveBatchPipeline(
                store,
                { server, ids ->
                    requests++
                    assertEquals("server", server)
                    assertEquals(3, ids.size)
                    assertEquals(ids.toSet(), store.saved)
                    assertEquals(ids.toSet(), store.pending)
                },
            )
        assertEquals(3, pipeline.accept(messages))
        assertEquals(1, requests)
        assertTrue(store.pending.isEmpty())
        assertEquals(0, pipeline.accept(messages))
        assertEquals(2, requests)
    }

    @Test
    fun partialStorageFailureLeavesPriorReceiptsAndSendsNoBatchConfirmation() {
        val store = Store().apply { failAt = "id-2" }
        var requests = 0
        assertThrows(IOException::class.java) {
            ReceiveBatchPipeline(store, { _, _ -> requests++ }).accept(messages)
        }
        assertEquals(setOf("id-1"), store.saved)
        assertEquals(store.saved, store.pending)
        assertEquals(0, requests)
    }

    @Test
    fun invalidLaterMemberIsRejectedBeforeAnyPersistence() {
        val store = Store()
        assertThrows(IllegalArgumentException::class.java) {
            ReceiveBatchPipeline(store, { _, _ -> fail("unexpected confirmation") })
                .accept(messages + IncomingMessage("server", "bad", "title", "x".repeat(65537)))
        }
        assertTrue(store.saved.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            ReceiveBatchPipeline(store, { _, _ -> }).accept(messages + messages.first())
        }
    }

    @Test
    fun transientConfirmationFailureKeepsEntireQueueButAuthenticationAndProtocolPropagate() {
        val store = Store()
        assertEquals(
            3,
            ReceiveBatchPipeline(store, { _, _ -> throw IOException("offline") }).accept(messages),
        )
        assertEquals(3, store.pending.size)
        assertThrows(SyncAuthenticationException::class.java) {
            ReceiveBatchPipeline(
                    store,
                    { _, _ -> throw SyncAuthenticationException(401, SyncStage.ACK) },
                )
                .accept(messages)
        }
        assertThrows(HttpRejectedException::class.java) {
            ReceiveBatchPipeline(store, { _, _ -> throw HttpRejectedException(400, SyncStage.ACK) })
                .accept(messages)
        }
        assertEquals(3, store.pending.size)
    }

    @Test
    fun batchRetiresEveryLocalReceiptThroughOneTransactionalCall() {
        val store = Store()
        val batches = mutableListOf<List<String>>()
        val pipeline =
            ReceiveBatchPipeline(
                store,
                { _, _ -> },
                acknowledgeCompleted = { _, ids -> batches.add(ids) },
            )
        assertEquals(3, pipeline.accept(messages))
        // One call for the whole page: the per-message fallback would delete twice per id.
        assertEquals(listOf(listOf("id-1", "id-2", "id-3")), batches)
    }

    @Test
    fun transientConfirmationFailureLeavesTheTransactionalBatchUntouched() {
        val store = Store()
        val batches = mutableListOf<List<String>>()
        val pipeline =
            ReceiveBatchPipeline(
                store,
                { _, _ -> throw IOException("offline") },
                acknowledgeCompleted = { _, ids -> batches.add(ids) },
            )
        assertEquals(3, pipeline.accept(messages))
        assertTrue(batches.isEmpty())
        assertEquals(3, store.pending.size)
    }

    @Test
    fun cancellationAfterLastSaveCannotConfirmTheBatch() {
        val store = Store()
        assertThrows(SyncCancelledException::class.java) {
            ReceiveBatchPipeline(
                    store,
                    { _, _ -> fail("unexpected confirmation") },
                    shouldContinue = { store.saved.size < 3 },
                )
                .accept(messages)
        }
        assertEquals(3, store.pending.size)
    }
}
