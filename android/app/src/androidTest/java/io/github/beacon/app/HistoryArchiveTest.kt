package io.github.beacon.app

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.*
import io.github.beacon.app.domain.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoryArchiveTest {
    private fun withStore(block: (RuntimeMessageStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-${java.util.UUID.randomUUID()}-"
        val context =
            object : ContextWrapper(base) {
                override fun getDatabasePath(path: String) = base.getDatabasePath(name + path)

                override fun openOrCreateDatabase(
                    path: String,
                    mode: Int,
                    factory: SQLiteDatabase.CursorFactory?,
                    handler: DatabaseErrorHandler?,
                ) = base.openOrCreateDatabase(name + path, mode, factory, handler)
            }
        RuntimeMessageStore(context).use { block(it) }
        base.deleteDatabase(name + "beacon-messages.db")
    }

    private fun message(id: String) =
        IncomingMessage(
            "history-server",
            id,
            "标题",
            "完整正文",
            1000,
            2000,
            subtitle = "副标题",
            bark = "{\"image\":\"https://example.com/a.png\"}",
            actions = listOf(MessageLinkAction("查看日志", "https://example.com/logs")),
        )

    @Test
    fun actionsPersistInDetailButDoNotBloatListProjection() {
        withStore { store ->
            val incoming = message("action")
            store.save(incoming)
            assertEquals(incoming.actions, store.detail(incoming.server, incoming.id)!!.actions)
            assertEquals(emptyList<MessageLinkAction>(), store.page(MessageQuery()).items.single().actions)
        }
    }

    @Test
    fun v13MigrationPreservesRowsAndDefaultsActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RuntimeMessageStore(context).use { helper ->
            SQLiteDatabase.create(null).use { db ->
                db.execSQL("CREATE TABLE messages(server TEXT NOT NULL,id TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL)")
                db.execSQL("INSERT INTO messages VALUES('server','existing','Title','Body')")
                helper.onUpgrade(db, 13, 14)
                db.rawQuery("SELECT id,title,body,actions FROM messages", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("existing", cursor.getString(0))
                    assertEquals("Title", cursor.getString(1))
                    assertEquals("Body", cursor.getString(2))
                    assertEquals("[]", cursor.getString(3))
                }
            }
        }
    }

    @Test
    fun oldArchiveWithoutActionsStillImports() {
        withStore { original ->
            original.save(message("legacy"))
            val archive = JSONObject(String(original.exportHistory(), Charsets.UTF_8))
            archive.getJSONArray("messages").getJSONObject(0).remove("actions")
            withStore { restored ->
                assertEquals(1, restored.importHistory(archive.toString().toByteArray()))
                assertEquals(emptyList<MessageLinkAction>(), restored.detail("history-server", "legacy")!!.actions)
            }
        }
    }

    @Test
    fun encryptedRoundTripPreservesReadStateAndDoesNotCreateReceiptsOrReminders() {
        withStore { original ->
            original.save(message("one"))
            original.mutate(
                MessageMutation(
                    MessageAction.MARK_READ,
                    listOf(MessageKey("history-server", "one")),
                )
            )
            val password = "archive-test-password".toCharArray()
            val encrypted = ArchiveCrypto.encrypt(original.exportHistory(), password)
            withStore { restored ->
                assertEquals(1, restored.importHistory(ArchiveCrypto.decrypt(encrypted, password)))
                val result = restored.detail("history-server", "one")!!
                assertTrue(result.isRead)
                assertEquals("完整正文", result.body)
                assertEquals(message("one").actions, result.actions)
                assertEquals(message("one").bark, result.bark)
                assertTrue(restored.pendingIds("history-server").isEmpty())
                assertEquals(0L, restored.pendingReminderCount())
                assertEquals(0, restored.importHistory(ArchiveCrypto.decrypt(encrypted, password)))
            }
        }
    }

    @Test
    fun localDeletionAndExistingContentsWin() {
        withStore { store ->
            store.save(message("one"))
            val snapshot = store.exportHistory()
            store.remove("history-server", "one")
            assertEquals(0, store.importHistory(snapshot))
            assertNull(store.detail("history-server", "one"))
        }
    }

    @Test
    fun malformedAndDatabaseFailureNeverPartiallyImport() {
        withStore { source ->
            source.save(message("one"))
            source.save(message("two"))
            val snapshot = source.exportHistory()
            val invalid = JSONObject(snapshot.toString(Charsets.UTF_8))
            invalid.getJSONArray("messages").getJSONObject(1).put("read", "false")
            withStore { destination ->
                assertTrue(
                    runCatching { destination.importHistory(invalid.toString().toByteArray()) }
                        .isFailure
                )
                assertTrue(destination.page(MessageQuery()).items.isEmpty())
                destination.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_import BEFORE INSERT ON messages WHEN NEW.id='two' BEGIN SELECT RAISE(ABORT,'test failure'); END"
                )
                assertTrue(runCatching { destination.importHistory(snapshot) }.isFailure)
                assertTrue(destination.page(MessageQuery()).items.isEmpty())
            }
        }
    }
}
