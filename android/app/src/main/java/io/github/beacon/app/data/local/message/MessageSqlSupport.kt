package io.github.beacon.app.data.local.message

import android.database.sqlite.SQLiteDatabase

/** Shared low-level SQLite execution helpers for the message-store helper classes. */
internal fun updateStatement(db: SQLiteDatabase, sql: String, args: Array<Any>): Int =
    db.compileStatement(sql).use { statement ->
        args.forEachIndexed { index, value ->
            if (value is String) statement.bindString(index + 1, value)
            else statement.bindLong(index + 1, (value as Number).toLong())
        }
        statement.executeUpdateDelete()
    }

internal fun rowExists(db: SQLiteDatabase, table: String, server: String, id: String): Boolean =
    db.query(
            table,
            arrayOf("id"),
            "server=? AND id=?",
            arrayOf(server, id),
            null,
            null,
            null,
            "1",
        )
        .use { it.moveToFirst() }
