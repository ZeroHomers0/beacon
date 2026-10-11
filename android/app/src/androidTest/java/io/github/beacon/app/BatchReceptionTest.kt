package io.github.beacon.app

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.receive.ReceiveBatchPipeline
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchReceptionTest {
    private fun withStore(block: (RuntimeMessageStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "batch-${java.util.UUID.randomUUID()}-"
        val context =
            object : ContextWrapper(base) {
                override fun getDatabasePath(name: String) = base.getDatabasePath(prefix + name)

                override fun openOrCreateDatabase(
                    name: String,
                    mode: Int,
                    factory: SQLiteDatabase.CursorFactory?,
                ) = base.openOrCreateDatabase(prefix + name, mode, factory)

                override fun openOrCreateDatabase(
                    name: String,
                    mode: Int,
                    factory: SQLiteDatabase.CursorFactory?,
                    handler: DatabaseErrorHandler?,
                ) = base.openOrCreateDatabase(prefix + name, mode, factory, handler)
            }
        try {
            RuntimeMessageStore(context).use(block)
        } finally {
            base.deleteDatabase(prefix + "beacon-messages.db")
        }
    }

    @Test
    fun durableBatchAndDeletedReplayDoNotRestoreMessages() = withStore { store ->
        val rows = (1..10).map { IncomingMessage("batch", "id$it", "title", "body") }
        store.writableDatabase.execSQL("INSERT INTO tombstones(server,id) VALUES('batch','id10')")
        var calls = 0
        val pipeline =
            ReceiveBatchPipeline(
                store,
                { server, ids ->
                    calls++
                    assertEquals(10, store.pendingIds(server).size)
                    assertEquals(rows.map { it.id }, ids)
                    assertNull(store.detail(server, "id10"))
                    assertNotNull(store.detail(server, "id1"))
                },
            )
        assertEquals(9, pipeline.accept(rows))
        assertTrue(store.pendingIds("batch").isEmpty())
        assertEquals(0, pipeline.accept(rows))
        assertEquals(2, calls)
        assertNull(store.detail("batch", "id10"))
    }

    @Test
    fun interruptedBatchKeepsFirstDurableReceipt() = withStore { store ->
        var saves = 0
        val wrapper =
            object : io.github.beacon.app.domain.receive.ReceiveStore {
                override fun save(
                    message: IncomingMessage
                ): io.github.beacon.app.domain.receive.SaveResult {
                    if (++saves == 2) throw java.io.IOException("injected storage failure")
                    return store.save(message)
                }

                override fun ackCompleted(server: String, id: String) =
                    store.ackCompleted(server, id)
            }
        var ack = false
        assertThrows(java.io.IOException::class.java) {
            ReceiveBatchPipeline(wrapper, { _, _ -> ack = true })
                .accept((1..3).map { IncomingMessage("batch", "id$it", "title", "body") })
        }
        assertFalse(ack)
        assertEquals(listOf("id1"), store.pendingIds("batch"))
        assertNotNull(store.detail("batch", "id1"))
        assertNull(store.detail("batch", "id2"))
    }
}
