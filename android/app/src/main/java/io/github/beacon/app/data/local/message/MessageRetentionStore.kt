package io.github.beacon.app.data.local.message

import android.database.sqlite.SQLiteOpenHelper

/** Redelivery contracts and expiry: how long a server may still reference retired receipts. */
internal class MessageRetentionStore(private val helper: SQLiteOpenHelper) {

    /** Called only with an authenticated server's supported maximum redelivery contract. */
    fun retireExpiredRecords(server: String, beforeMs: Long): Int {
        require(server.matches(Regex("[0-9a-f]{64}")) && beforeMs > 0)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val keys =
                db.rawQuery(
                        "SELECT id FROM receipt_times WHERE server=? AND created>0 AND created<? ORDER BY created LIMIT 100",
                        arrayOf(server, beforeMs.toString()),
                    )
                    .use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    }
            var retired = 0
            keys.forEach { id ->
                retired += db.delete("pending_ack", "server=? AND id=?", arrayOf(server, id))
                db.delete("tombstones", "server=? AND id=?", arrayOf(server, id))
                db.delete("receipt_times", "server=? AND id=?", arrayOf(server, id))
            }
            if (keys.isNotEmpty())
                db.execSQL(
                    "INSERT OR IGNORE INTO retention_contracts(server) VALUES(?)",
                    arrayOf(server),
                )
            db.setTransactionSuccessful()
            return retired
        } finally {
            db.endTransaction()
        }
    }

    fun requiresRetentionContract(server: String): Boolean =
        helper.readableDatabase
            .rawQuery("SELECT 1 FROM retention_contracts WHERE server=?", arrayOf(server))
            .use { it.moveToFirst() }
}
