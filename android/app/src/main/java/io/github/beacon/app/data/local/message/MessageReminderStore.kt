package io.github.beacon.app.data.local.message

import android.database.sqlite.SQLiteOpenHelper
import io.github.beacon.app.data.local.sql.MessageManagementSql
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MuteScope
import io.github.beacon.app.domain.model.MuteKind
import io.github.beacon.app.domain.receive.NotificationIntent

/** SQLite's compiled statement-variable ceiling; chunked so a large batch stays one commit. */
private const val SQLITE_MAX_VARIABLES = 500

/** Durable message reminders, their consumption and notification-driven read marking. */
internal class MessageReminderStore(private val helper: SQLiteOpenHelper) {

    /** Suppress the whole scoped backlog, not just the first 200 visible reminders. */
    fun suppressNotifications(scopes:List<MuteScope>) {
        require(scopes.size<=1000)
        scopes.forEach{it.validate()}
        val eventScopes=scopes.filter{it.kind!=MuteKind.TOPIC}
        if(eventScopes.isEmpty())return
        val db=helper.writableDatabase
        db.beginTransaction()
        try {
            if(eventScopes.any{it.kind==MuteKind.GLOBAL})db.delete("notification_intents",null,null)
            else for(scope in eventScopes)db.execSQL(
                "DELETE FROM notification_intents WHERE server=? AND id IN (SELECT id FROM messages WHERE server=? AND application_id=?)",
                arrayOf(scope.source,scope.source,scope.identity))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }

    fun pendingNotifications(): List<NotificationIntent> =
        helper.readableDatabase
            .rawQuery(
                "SELECT n.seq,m.source_created,m.title,m.body,m.server,m.id,m.priority,m.application_id FROM notification_intents n JOIN messages m ON m.server=n.server AND m.id=n.id WHERE m.delete_token='' ORDER BY n.seq LIMIT 200",
                null,
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        NotificationIntent(
                            cursor.getLong(0),
                            cursor.getLong(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            MessageKey(
                                cursor.getString(4),
                                cursor.getString(5),
                            ),
                            priority = cursor.getInt(6),
                            applicationId = cursor.getString(7),
                        )
                    )
                }
            }

    /** Durable single-message reminder; never changes unread state or receive confirmations. */
    fun requestReminder(key: MessageKey): NotificationIntent? {
        key.validate()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                MessageManagementSql.REQUEST_REMINDER,
                arrayOf(key.server, key.id, key.server, key.id),
            )
            val result =
                db.rawQuery(
                        MessageManagementSql.REMINDER,
                        arrayOf(key.server, key.id),
                    )
                    .use { cursor ->
                        if (cursor.moveToFirst())
                            NotificationIntent(
                                cursor.getLong(0),
                                cursor.getLong(1),
                                cursor.getString(2),
                                cursor.getString(3),
                                key,
                                priority=cursor.getInt(4),
                                applicationId=cursor.getString(5),
                            )
                        else null
                    }
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun notificationsPosted(intents: List<NotificationIntent>) {
        if (intents.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            // One statement for the whole batch. Deleting row by row made the cost grow with the
            // backlog — up to 200 executions inside a single commit for a catch-up flush.
            intents
                .map { it.sequence }
                .chunked(SQLITE_MAX_VARIABLES)
                .forEach { batch ->
                    db.delete(
                        "notification_intents",
                        "seq IN (${batch.joinToString(",") { "?" }})",
                        batch.map { it.toString() }.toTypedArray(),
                    )
                }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** A visible notification was handled: mark read and consume its reminder atomically. */
    fun readFromNotification(key: MessageKey): Int = readFromNotification(listOf(key))

    /** A summary notification stands for a whole batch; one transaction keeps its rows consistent. */
    fun readFromNotification(keys: List<MessageKey>): Int {
        if (keys.isEmpty()) return 0
        keys.forEach { it.validate() }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            var changed = 0
            keys.forEach { key ->
                changed +=
                    updateStatement(db, MessageManagementSql.READ, arrayOf(1L, key.server, key.id, 1L))
                db.execSQL(MessageManagementSql.CANCEL_NOTICE, arrayOf(key.server, key.id))
            }
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }
}
