package io.github.beacon.app.data.local.message

import android.database.sqlite.SQLiteOpenHelper
import io.github.beacon.app.data.local.sql.MessageQuerySql
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessagePage
import io.github.beacon.app.domain.model.MessageQuery

/** Read-only projections over the message, reminder and sync-cursor tables. */
internal class MessageQueryStore(private val helper: SQLiteOpenHelper) {

    fun recent(): List<IncomingMessage> =
        helper.readableDatabase
            .rawQuery(
                "SELECT server,id,title,substr(body,1,2000) FROM messages WHERE delete_token='' ORDER BY received DESC,server,id LIMIT 20",
                null,
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        IncomingMessage(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3),
                        )
                    )
                }
            }

    fun page(query: MessageQuery): MessagePage {
        query.validate()
        val db = helper.readableDatabase
        val anchorRow =
            query.anchor?.let { key ->
                db.rawQuery(
                        "SELECT rowid FROM messages WHERE server=? AND id=? AND delete_token=''",
                        arrayOf(key.server, key.id),
                    )
                    .use {
                        if (it.moveToFirst()) it.getLong(0)
                        else null
                    }
            }
        val anchored = anchorRow?.let { query.copy(before = it + 1, anchor = null) } ?: query
        val (countSql, countArgs) = MessageQuerySql.counts(query, anchorRow ?: Long.MAX_VALUE)
        val (total, unread, newer) =
            db.rawQuery(countSql, countArgs).use {
                it.moveToFirst()
                Triple(it.getLong(0),it.getLong(1),it.getLong(2))
            }
        val (pageSql, pageArgs) = MessageQuerySql.page(anchored)
        val rows =
            db.rawQuery(pageSql, pageArgs).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        cursor.getLong(0) to
                            IncomingMessage(
                                cursor.getString(1),
                                cursor.getString(2),
                                cursor.getString(3),
                                cursor.getString(4),
                                cursor.getLong(5),
                                cursor.getLong(6),
                                cursor.getInt(7) == 1,
                                cursor.getString(8),
                                cursor.getInt(9),
                                cursor.getInt(10) == 1,
                                cursor.getString(11),
                                cursor.getString(12),
                                cursor.getString(13),
                                cursor.getLong(14),
                                cursor.getString(15),
                            )
                    )
                }
            }
        val visible = rows.take(MessageQuery.PAGE_SIZE)
        return MessagePage(
            visible.map { it.second },
            total,
            if (rows.size > MessageQuery.PAGE_SIZE) visible.last().first else null,
            unread,
            null,
            newer,
        )
    }

    fun detail(server: String, id: String): IncomingMessage? =
        helper.readableDatabase
            .rawQuery(
                MessageQuerySql.DETAIL,
                arrayOf(server, id),
            )
            .use { cursor ->
                if (!cursor.moveToFirst()) null
                else
                    IncomingMessage(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getLong(4),
                        cursor.getLong(5),
                        cursor.getInt(6) == 1,
                        cursor.getString(7),
                        cursor.getInt(8),
                        cursor.getInt(9) == 1,
                        cursor.getString(10),
                        cursor.getString(11),
                        cursor.getString(12),
                        cursor.getLong(13),
                        cursor.getString(14),
                        structuredBlocks = cursor.getString(16),
                        actions =
                            io.github.beacon.app.data.remote.MessageActionsCodec.decode(
                                cursor.getString(15)
                            ),
                    )
            }

    fun pendingReminderCount(): Long =
        helper.readableDatabase.rawQuery("SELECT count(*) FROM notification_intents", null).use {
            it.moveToFirst()
            it.getLong(0)
        }

    fun cursor(server: String): Long =
        helper.readableDatabase
            .rawQuery("SELECT sequence FROM sync_cursors WHERE server=?", arrayOf(server))
            .use { if (it.moveToFirst()) it.getLong(0) else 0 }

    fun advanceCursor(server: String, sequence: Long) {
        require(sequence >= cursor(server))
        helper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO sync_cursors(server,sequence) VALUES(?,?)",
            arrayOf<Any>(server, sequence),
        )
    }

    fun pendingIds(server: String): List<String> =
        helper.readableDatabase
            .rawQuery(
                "SELECT id FROM pending_ack WHERE server=? ORDER BY id LIMIT 100",
                arrayOf(server),
            )
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
}
