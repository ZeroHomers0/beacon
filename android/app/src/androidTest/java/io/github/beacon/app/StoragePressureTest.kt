package io.github.beacon.app

import android.database.sqlite.SQLiteFullException
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.receive.SaveResult
import org.junit.Assert.*
import org.junit.Test

class StoragePressureTest {
    @Test
    fun lowSpaceLeavesNewMessageUnconfirmedButAllowsDuplicateConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = "pressure-" + java.util.UUID.randomUUID()
        val message = IncomingMessage(server, "existing", "title", "body")
        RuntimeMessageStore(context, { Long.MAX_VALUE }).use {
            assertEquals(SaveResult.INSERTED, it.save(message))
        }
        RuntimeMessageStore(context, { 0 }).use { store ->
            assertEquals(SaveResult.DUPLICATE, store.save(message))
            assertThrows(SQLiteFullException::class.java) { store.save(message.copy(id = "new")) }
            assertTrue(store.pendingIds(server).contains("existing"))
            assertFalse(store.pendingIds(server).contains("new"))
            store.ackCompleted(server, "existing")
            assertFalse(store.pendingIds(server).contains("existing"))
        }
    }
}
