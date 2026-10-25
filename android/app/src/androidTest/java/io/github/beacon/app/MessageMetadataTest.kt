package io.github.beacon.app

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageQuery
import io.github.beacon.app.ipc.readMessage
import io.github.beacon.app.runtime.RuntimeProbeService
import org.junit.Assert.*
import org.junit.Test

class MessageMetadataTest {
    private fun withContext(block: (ContextWrapper) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "metadata-${java.util.UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = base.getDatabasePath(prefix + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) = base.openOrCreateDatabase(prefix + name, mode, factory, handler)
        }
        try { block(context) } finally { base.deleteDatabase(prefix + "beacon-messages.db") }
    }
    private fun message(id: String, group: String, app: String = "a".repeat(64)) = IncomingMessage("server", id, "build complete", "body", 1000, 2000, applicationId = app, applicationName = "自动构建", group = group, expiresAtMs = 60000)

    @Test fun metadataSurvivesStorageArchiveAndExactFiltering() = withContext { context ->
        RuntimeMessageStore(context).use { store ->
            val one = message("one", "NAS' OR 1=1 --")
            store.save(one);store.save(message("two", "NAS", "b".repeat(64)))
            assertEquals(one.applicationId, store.detail("server", "one")!!.applicationId)
            assertEquals(one.group, store.detail("server", "one")!!.group)
            assertEquals(60000, store.detail("server", "one")!!.expiresAtMs)
            assertEquals(listOf("one"), store.page(MessageQuery("group:${one.group}")).items.map { it.id })
            assertEquals(listOf("one"), store.page(MessageQuery("app:${one.applicationId}")).items.map { it.id })
            assertEquals(2, store.page(MessageQuery("自动构建")).items.size)
            val archive = store.exportHistory()
            withContext { restoredContext -> RuntimeMessageStore(restoredContext).use { restored ->
                assertEquals(2, restored.importHistory(archive))
                val result = restored.detail("server", "one")!!
                assertEquals(one.group, result.group);assertEquals(one.applicationId, result.applicationId);assertEquals(one.applicationName, result.applicationName);assertEquals(one.expiresAtMs, result.expiresAtMs)
                assertTrue(restored.pendingIds("server").isEmpty())
            } }
        }
    }
    @Test fun versionTwelveUpgradePreservesHistoryReceiptsAndReminders() = withContext { context ->
        RuntimeMessageStore(context).use { it.save(IncomingMessage("server", "legacy", "title", "body")) }
        SQLiteDatabase.openDatabase(context.getDatabasePath("beacon-messages.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // The fixture starts from today's schema; v12 did not contain these indexes.
            db.execSQL("DROP INDEX IF EXISTS messages_application")
            db.execSQL("DROP INDEX IF EXISTS messages_group")
            for (column in listOf("application_id", "application_name", "message_group", "expires_at", "actions", "blocks")) db.execSQL("ALTER TABLE messages DROP COLUMN $column")
            db.version = 12
        }
        RuntimeMessageStore(context).use { store ->
            val row = store.detail("server", "legacy")!!
            assertEquals("body", row.body);assertEquals("", row.group);assertEquals("", row.applicationId)
            assertEquals(listOf("legacy"), store.pendingIds("server"));assertEquals(1L, store.pendingReminderCount())
        }
    }
    @Test fun binderMessageCarriesMetadata() {
        val original = message("one", "CI")
        val parcel = Parcel.obtain()
        try {
            val write = RuntimeProbeService::class.java.getDeclaredMethod("writeMessage", Parcel::class.java, IncomingMessage::class.java).apply { isAccessible = true }
            write.invoke(RuntimeProbeService(), parcel, original)
            parcel.setDataPosition(0)
            assertEquals(original, parcel.readMessage())
            assertEquals(0, parcel.dataAvail())
        } finally { parcel.recycle() }
    }
    @Test fun scopedMuteConsumesMoreThanOnePageWithoutChangingHistoryOrAck() = withContext { context ->
        RuntimeMessageStore(context).use { store ->
            val app="a".repeat(64)
            val messages=(1..201).map{IncomingMessage("server","muted-$it","title","body",applicationId=app)}+
                IncomingMessage("server","other-app","title","body",applicationId="b".repeat(64))+
                IncomingMessage("other-server","other-source","title","body",applicationId=app)
            messages.chunked(io.github.beacon.app.domain.receive.ReceiveBatchPipeline.MAX_BATCH_SIZE).forEach{store.saveBatch(it){true}}
            assertEquals(203L,store.pendingReminderCount())
            store.suppressNotifications(listOf(io.github.beacon.app.domain.model.MuteScope(
                io.github.beacon.app.domain.model.MuteKind.APPLICATION,"server",app)))
            assertEquals(2L,store.pendingReminderCount())
            assertEquals(setOf("other-app","other-source"),store.pendingNotifications().map{it.key!!.id}.toSet())
            for(table in listOf("messages","pending_ack"))store.readableDatabase.rawQuery("SELECT count(*) FROM $table",null).use{
                assertTrue(it.moveToFirst());assertEquals(203,it.getInt(0))
            }
            store.suppressNotifications(listOf(io.github.beacon.app.domain.model.MuteScope.GLOBAL))
            assertEquals(0L,store.pendingReminderCount())
        }
    }
}
