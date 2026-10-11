package io.github.beacon.app.data.local.message

import android.database.sqlite.SQLiteOpenHelper
import io.github.beacon.app.data.local.sql.MessageManagementSql
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.receive.NotificationIntent

/** Durable message reminders, their consumption and notification-driven read marking. */
internal class MessageReminderStore(private val helper: SQLiteOpenHelper) {

    fun pendingNotifications(): List<NotificationIntent> =
        helper.readableDatabase
            .rawQuery(
                "SELECT n.seq,m.source_created,m.title,m.body,m.server,m.id,m.priority FROM notification_intents n JOIN messages m ON m.server=n.server AND m.id=n.id WHERE m.delete_token='' ORDER BY n.seq LIMIT 200",
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
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            intents.forEach {
                db.delete("notification_intents", "seq=?", arrayOf(it.sequence.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** A visible notification was handled: mark read and consume its reminder atomically. */
    fun readFromNotification(key: MessageKey): Int {
        key.validate()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val changed =
                updateStatement(db, MessageManagementSql.READ, arrayOf(1L, key.server, key.id, 1L))
            db.execSQL(MessageManagementSql.CANCEL_NOTICE, arrayOf(key.server, key.id))
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }
}
