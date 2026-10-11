package io.github.beacon.app

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.receive.SaveResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageCleanupTest {
    private fun withStore(block: (RuntimeMessageStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "cleanup-${java.util.UUID.randomUUID()}-"
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

    private fun seed(
        store: RuntimeMessageStore,
        id: String,
        read: Boolean = true,
        server: String = "a",
    ) {
        store.writableDatabase.execSQL(
            "INSERT INTO messages(server,id,title,body,received,is_read) VALUES(?,?, 'title','body',1,?)",
            arrayOf<Any>(server, id, if (read) 1 else 0),
        )
    }

    @Test
    fun usageSnapshotCountsOverlappingProtectionAndRetainsDeletionDeduplication() =
        withStore { store ->
            val message = IncomingMessage("usage-test", "protected", "title", "body")
            assertEquals(SaveResult.INSERTED, store.save(message))
            store.writableDatabase.execSQL(
                "INSERT INTO tombstones(server,id) VALUES('usage-test','deleted')"
            )
            val preview = store.previewCleanup(CleanupPolicy())
            assertEquals(1L, preview.total)
            assertEquals(1L, preview.unread)
            assertEquals(1L, preview.pending)
            assertEquals(1L, preview.pendingNotifications)
            assertEquals(1L, preview.tombstones)
            assertTrue(preview.databaseBytes >= preview.reusableBytes)
            assertTrue(preview.availableBytes > 0)
            assertTrue(preview.sampledAtMs > 0)
            assertTrue(preview.walBytes >= 0)
            assertEquals(SaveResult.DELETED, store.save(message.copy(id = "deleted")))
            assertNull(store.detail("usage-test", "deleted"))
        }

    @Test
    fun smallCustomCapacityStillProtectsUnreadReceiptsAndNotices() = withStore { store ->
        seed(store, "safe")
        seed(store, "unread", false)
        seed(store, "pending")
        seed(store, "notice")
        store.writableDatabase.execSQL("INSERT INTO pending_ack VALUES('a','pending')")
        store.writableDatabase.execSQL(
            "INSERT INTO notification_intents(server,id) VALUES('a','notice')"
        )
        val policy = CleanupPolicy(0, 1, true)
        assertEquals(1, store.automaticCleanup(policy).changed)
        assertNotNull(store.detail("a", "unread"))
        assertNotNull(store.detail("a", "pending"))
        assertNotNull(store.detail("a", "notice"))
    }

    @Test
    fun automaticBatchPreservesProtectionsAndManualPreview() = withStore { store ->
        seed(store, "safe")
        seed(store, "unread", false)
        seed(store, "pending")
        seed(store, "notice")
        store.writableDatabase.execSQL("INSERT INTO pending_ack VALUES('a','pending')")
        store.writableDatabase.execSQL(
            "INSERT INTO notification_intents(server,id) VALUES('a','notice')"
        )
        val preview = store.previewCleanup(CleanupPolicy())
        assertEquals(1, store.automaticCleanup(CleanupPolicy()).changed)
        assertEquals(0, store.applyCleanup(preview.token).changed)
        assertNotNull(store.detail("a", "unread"))
        assertNotNull(store.detail("a", "pending"))
        assertNotNull(store.detail("a", "notice"))
    }

    @Test
    fun automaticBatchIsBounded() = withStore { store ->
        repeat(105) { seed(store, "safe-$it") }
        assertEquals(100, store.automaticCleanup(CleanupPolicy()).changed)
        assertEquals(5, store.automaticCleanup(CleanupPolicy()).changed)
    }

    @Test
    fun settingsDefaultOffAndPersistAcrossStoreInstances() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "cleanup-settings-${java.util.UUID.randomUUID()}"
        val context =
            object : ContextWrapper(base) {
                override fun getSharedPreferences(file: String, mode: Int) =
                    base.getSharedPreferences(name, mode)
            }
        try {
            val first = io.github.beacon.app.data.local.CleanupPreferenceStore(context)
            assertFalse(first.settings().automatic)
            first.save(CleanupSettings(CleanupPolicy(7, 1000, false), true))
            first.record("已清理 2 条本地消息")
            val reopened =
                io.github.beacon.app.data.local.CleanupPreferenceStore(context).settings()
            assertEquals(CleanupPolicy(7, 1000, false), reopened.policy)
            assertTrue(reopened.automatic)
            assertEquals("已清理 2 条本地消息", reopened.lastResult)
            context.getSharedPreferences("storage_cleanup", 0).edit().putInt("days", -1).commit()
            assertFalse(
                io.github.beacon.app.data.local.CleanupPreferenceStore(context).settings().automatic
            )
        } finally {
            base.deleteSharedPreferences(name)
        }
    }

    @Test
    fun previewProtectsUnreadPendingAndNotificationsAndDeletesPermanently() = withStore { store ->
        seed(store, "safe")
        seed(store, "unread", false)
        seed(store, "pending")
        seed(store, "notice")
        store.writableDatabase.execSQL("INSERT INTO pending_ack VALUES('a','pending')")
        store.writableDatabase.execSQL(
            "INSERT INTO notification_intents(server,id) VALUES('a','notice')"
        )
        val preview = store.previewCleanup(CleanupPolicy())
        assertEquals(4L, preview.total)
        assertEquals(1L, preview.eligible)
        assertEquals(3L, preview.protected)
        assertNotNull(store.detail("a", "safe"))
        val result = store.applyCleanup(preview.token)
        assertEquals(1, result.changed)
        assertNull(result.undo)
        assertNull(store.detail("a", "safe"))
        assertNotNull(store.detail("a", "unread"))
        assertNotNull(store.detail("a", "pending"))
        assertNotNull(store.detail("a", "notice"))
        assertEquals(SaveResult.DELETED, store.save(IncomingMessage("a", "safe", "again", "body")))
        assertNull(store.detail("a", "safe"))
        assertThrows(IllegalArgumentException::class.java) { store.applyCleanup(preview.token) }
    }

    @Test
    fun executionRechecksProtectionAndNeverAddsNewCandidates() = withStore { store ->
        seed(store, "became-unread")
        seed(store, "became-pending")
        seed(store, "safe")
        seed(store, "safe", server = "b")
        val preview = store.previewCleanup(CleanupPolicy())
        seed(store, "new-after-preview")
        store.writableDatabase.execSQL("UPDATE messages SET is_read=0 WHERE id='became-unread'")
        store.writableDatabase.execSQL("INSERT INTO pending_ack VALUES('a','became-pending')")
        assertEquals(2, store.applyCleanup(preview.token).changed)
        assertNotNull(store.detail("a", "new-after-preview"))
        assertNotNull(store.detail("a", "became-unread"))
        assertNotNull(store.detail("a", "became-pending"))
        assertNull(store.detail("a", "safe"))
        assertNull(store.detail("b", "safe"))
    }

    @Test
    fun permanentRetentionStillHonorsCountAndBatchesAreBounded() = withStore { store ->
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            repeat(1200) { seed(store, "$it") }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val preview = store.previewCleanup(CleanupPolicy(days = 0, maxMessages = 1000))
        assertEquals(200L, preview.eligible)
        assertEquals(100, preview.planned)
        assertEquals(100, store.applyCleanup(preview.token).changed)
        val second = store.previewCleanup(CleanupPolicy(days = 0, maxMessages = 1000))
        assertEquals(100L, second.eligible)
        assertEquals(100, store.applyCleanup(second.token).changed)
        assertEquals(0L, store.previewCleanup(CleanupPolicy(days = 0, maxMessages = 1000)).eligible)
    }

    @Test
    fun disablingUnreadProtectionKeepsConfirmationProtectionAndOldTokensFail() =
        withStore { store ->
            seed(store, "unread", false)
            seed(store, "pending", false)
            store.writableDatabase.execSQL("INSERT INTO pending_ack VALUES('a','pending')")
            val original = store.previewCleanup(CleanupPolicy())
            val changed = store.previewCleanup(CleanupPolicy(keepUnread = false))
            assertEquals(1L, changed.eligible)
            assertThrows(IllegalArgumentException::class.java) {
                store.applyCleanup(original.token)
            }
            assertEquals(1, store.applyCleanup(changed.token).changed)
            assertNotNull(store.detail("a", "pending"))
        }

    @Test
    fun storageFailureRollsBackDeletionAndTombstones() = withStore { store ->
        seed(store, "safe")
        val preview = store.previewCleanup(CleanupPolicy())
        store.writableDatabase.execSQL(
            "CREATE TRIGGER block_tombstone BEFORE INSERT ON tombstones BEGIN SELECT RAISE(ABORT,'blocked'); END"
        )
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            store.applyCleanup(preview.token)
        }
        assertNotNull(store.detail("a", "safe"))
        store.readableDatabase.rawQuery("SELECT count(*) FROM tombstones", null).use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }
}
