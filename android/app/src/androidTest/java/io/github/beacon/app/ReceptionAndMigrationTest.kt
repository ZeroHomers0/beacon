package io.github.beacon.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.ReceivePlanStore
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceptionAndMigrationTest {
    @Test
    fun allReceiveModesPersistWithoutStartingNetworkFromTheStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReceivePlanStore(context)
        val original = store.load()
        try {
            val plan = ReceivePlan(ReceiveMode.RULES, false, true)
            store.save(plan)
            assertEquals(plan, ReceivePlanStore(context).load())
            val realtime = plan.copy(mode = ReceiveMode.REALTIME)
            store.save(realtime)
            assertEquals(realtime, store.load())
        } finally {
            store.save(original)
        }
    }

    @Test
    fun upgradeDropsOnlySourcePreferencesAndKeepsServerScopedData() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "migration-${java.util.UUID.randomUUID()}-"
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
                    errorHandler: DatabaseErrorHandler?,
                ) = base.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
            }
        try {
            context.openOrCreateDatabase("beacon-messages.db", Context.MODE_PRIVATE, null).use { db
                ->
                RuntimeMessageStore(context).use { it.onCreate(db) }
                // Reconstruct the real v7 schema, which did not contain priority.
                db.execSQL("ALTER TABLE messages DROP COLUMN priority")
                db.execSQL("ALTER TABLE messages DROP COLUMN bark")
                db.execSQL(
                    "CREATE TABLE source_preferences(server TEXT PRIMARY KEY,name TEXT,pinned INTEGER,icon INTEGER)"
                )
                db.execSQL("INSERT INTO source_preferences VALUES('a','legacy',1,7)")
                db.execSQL(
                    "INSERT INTO messages(server,id,title,body,received,is_read) VALUES('a','same','A','body',1,1),('b','same','B','body',2,0)"
                )
                db.execSQL("INSERT INTO pending_ack VALUES('a','same'),('b','same')")
                db.execSQL("INSERT INTO sync_cursors VALUES('a',42),('b',43)")
                db.execSQL("INSERT INTO tombstones VALUES('a','deleted')")
                db.execSQL(
                    "CREATE TABLE undo_batches(token TEXT PRIMARY KEY,created INTEGER NOT NULL,expires INTEGER NOT NULL)"
                )
                db.execSQL("INSERT INTO undo_batches VALUES('undo',1,9999999999999)")
                db.execSQL(
                    "INSERT INTO messages(server,id,title,body,received,delete_token) VALUES('a','hidden','Deleted','body',1,'undo')"
                )
                db.version = 7
            }
            RuntimeMessageStore(context).use { store ->
                val db = store.writableDatabase
                assertEquals(12, db.version)
                fun count(sql: String): Int =
                    db.rawQuery(sql, null).use {
                        it.moveToFirst()
                        it.getInt(0)
                    }
                assertEquals(
                    0,
                    count("SELECT count(*) FROM sqlite_master WHERE name='source_preferences'"),
                )
                assertEquals(2, count("SELECT count(*) FROM messages WHERE id='same'"))
                assertEquals(
                    1,
                    count("SELECT is_read FROM messages WHERE server='a' AND id='same'"),
                )
                assertEquals(2, count("SELECT count(*) FROM pending_ack"))
                assertEquals(2, count("SELECT count(*) FROM sync_cursors"))
                assertEquals(2, count("SELECT count(*) FROM tombstones"))
                assertEquals(0, count("SELECT count(*) FROM messages WHERE id='hidden'"))
                assertEquals(
                    0,
                    count("SELECT count(*) FROM sqlite_master WHERE name='undo_batches'"),
                )
                assertEquals("A", store.detail("a", "same")?.title)
                assertEquals("B", store.detail("b", "same")?.title)
                assertEquals(5, store.detail("a", "same")?.priority)
            }
        } finally {
            base.deleteDatabase(prefix + "beacon-messages.db")
        }
    }
}
