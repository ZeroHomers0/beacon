package io.github.beacon.app

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.receive.SaveResult
import org.junit.Assert.*
import org.junit.Test

class ClearAllMessagesTest {
    private fun withStore(test: (RuntimeMessageStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "clear-all-${java.util.UUID.randomUUID()}-"
        val context =
            object : ContextWrapper(base) {
                override fun getDatabasePath(name: String) = base.getDatabasePath(prefix + name)

                override fun openOrCreateDatabase(
                    name: String,
                    mode: Int,
                    factory: SQLiteDatabase.CursorFactory?,
                    errorHandler: DatabaseErrorHandler?,
                ) = base.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
            }
        val store = RuntimeMessageStore(context)
        try {
            test(store)
        } finally {
            store.close()
            base.deleteDatabase(prefix + "beacon-messages.db")
        }
    }

    @Test
    fun clearAllPreservesReceiptsCursorsAndPreventsResurrection() = withStore { store ->
        val message =
            IncomingMessage(
                "a".repeat(64),
                "b".repeat(64),
                "title",
                "body",
                System.currentTimeMillis(),
            )
        assertEquals(SaveResult.INSERTED, store.save(message))
        store.advanceCursor(message.server, 42)
        assertTrue(store.detail(message.server, message.id)!!.reminderPending)
        assertEquals(1L, store.clearAllMessages())
        assertTrue(store.page(MessageQuery()).items.isEmpty())
        assertEquals(0L, store.pendingReminderCount())
        assertEquals(listOf(message.id), store.pendingIds(message.server))
        assertEquals(42L, store.cursor(message.server))
        assertEquals(SaveResult.DELETED, store.save(message))
        assertEquals(0L, store.clearAllMessages())
    }

    @Test
    fun storageFailureRollsBackDeletionAndDeduplicationTogether() = withStore { store ->
        val message =
            IncomingMessage(
                "a".repeat(64),
                "b".repeat(64),
                "title",
                "body",
                System.currentTimeMillis(),
            )
        store.save(message)
        store.writableDatabase.execSQL(
            "CREATE TRIGGER reject_all_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT,'fixture'); END"
        )
        assertTrue(runCatching { store.clearAllMessages() }.isFailure)
        assertNotNull(store.detail(message.server, message.id))
        assertEquals(1L, store.pendingReminderCount())
        store.readableDatabase.rawQuery("SELECT count(*) FROM tombstones", null).use {
            it.moveToFirst()
            assertEquals(0L, it.getLong(0))
        }
        store.writableDatabase.execSQL("DROP TRIGGER reject_all_delete")
        assertEquals(1L, store.clearAllMessages())
    }
}
