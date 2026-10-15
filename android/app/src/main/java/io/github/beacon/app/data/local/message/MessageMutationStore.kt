package io.github.beacon.app.data.local.message

import android.database.sqlite.SQLiteOpenHelper
import io.github.beacon.app.data.local.sql.MessageManagementSql
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageMutation
import io.github.beacon.app.domain.model.MessageMutationResult

/** Explicit message mutations: local deletion with tombstoning and read-state changes. */
internal class MessageMutationStore(private val helper: SQLiteOpenHelper) {

    /** One atomic update across every server and page; receipts and notices remain unchanged. */
    fun markAllRead(): Long =
        updateStatement(helper.writableDatabase, MessageManagementSql.MARK_ALL_READ, emptyArray()).toLong()

    fun remove(server: String, id: String) {
        mutate(MessageMutation(MessageAction.DELETE, listOf(MessageKey(server, id))))
    }

    fun mutate(request: MessageMutation): MessageMutationResult {
        request.validate()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            var changed = 0
            for (key in request.keys) {
                if (request.action == MessageAction.DELETE) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO receipt_times(server,id,created,deleted) SELECT server,id,source_created,1 FROM messages WHERE server=? AND id=? AND source_created>0",
                        arrayOf(key.server, key.id),
                    )
                    db.execSQL(
                        "UPDATE receipt_times SET deleted=1 WHERE server=? AND id=?",
                        arrayOf(key.server, key.id),
                    )
                    val count =
                        updateStatement(
                            db,
                            MessageManagementSql.DELETE,
                            arrayOf(key.server, key.id),
                        )
                    if (count > 0) {
                        db.execSQL(MessageManagementSql.TOMBSTONE, arrayOf(key.server, key.id))
                        db.execSQL(MessageManagementSql.CANCEL_NOTICE, arrayOf(key.server, key.id))
                    }
                    changed += count
                } else {
                    val read = if (request.action == MessageAction.MARK_READ) 1L else 0L
                    changed +=
                        updateStatement(
                            db,
                            MessageManagementSql.READ,
                            arrayOf(read, key.server, key.id, read),
                        )
                }
            }
            db.setTransactionSuccessful()
            return MessageMutationResult(changed)
        } finally {
            db.endTransaction()
        }
    }

    /** Explicit destructive action; preserve cursors, pending confirmations and deduplication. */
    fun clearAllMessages(): Long {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val count =
                db.rawQuery("SELECT count(*) FROM messages", null).use {
                    it.moveToFirst()
                    it.getLong(0)
                }
            db.execSQL(
                "INSERT OR IGNORE INTO receipt_times(server,id,created,deleted) SELECT server,id,source_created,1 FROM messages WHERE source_created>0"
            )
            db.execSQL(
                "UPDATE receipt_times SET deleted=1 WHERE EXISTS(SELECT 1 FROM messages m WHERE m.server=receipt_times.server AND m.id=receipt_times.id)"
            )
            db.execSQL("INSERT OR IGNORE INTO tombstones(server,id) SELECT server,id FROM messages")
            db.execSQL("DELETE FROM notification_intents")
            db.execSQL("DELETE FROM messages")
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }
}
