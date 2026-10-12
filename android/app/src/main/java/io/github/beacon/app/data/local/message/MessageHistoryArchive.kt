package io.github.beacon.app.data.local.message

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.beacon.app.domain.model.IncomingMessage

/** Portable history snapshot and its atomic, non-destructive merge back into the helper. */
internal class MessageHistoryArchive(
    private val helper: SQLiteOpenHelper,
    private val availableBytes: () -> Long,
    private val detail: (String, String) -> IncomingMessage?,
    private val onImported: () -> Unit,
    private val maxBytes: Int,
) {

    /** Portable history snapshot; no live confirmation queue or credentials are replayed. */
    fun exportHistory(): ByteArray {
        val db = helper.readableDatabase
        db.beginTransaction()
        try {
            val rows = org.json.JSONArray()
            var estimatedBytes = 256L
            db.rawQuery(
                    "SELECT server,id,title,body,source_created,received,is_read,subtitle,priority,bark,application_id,message_group,expires_at,application_name,actions FROM messages WHERE delete_token='' ORDER BY rowid",
                    null,
                )
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        require(rows.length() < 100_000)
                        val row =
                            (org.json
                                .JSONObject()
                                .put("server", cursor.getString(0))
                                .put("id", cursor.getString(1))
                                .put("title", cursor.getString(2))
                                .put("body", cursor.getString(3))
                                .put("created", cursor.getLong(4))
                                .put("received", cursor.getLong(5))
                                .put("read", cursor.getInt(6) == 1)
                                .put("subtitle", cursor.getString(7))
                                .put("priority", cursor.getInt(8))
                                .put("bark", cursor.getString(9))
                                .put("application_id", cursor.getString(10))
                                .put("group", cursor.getString(11))
                                .put("expires_at_ms", cursor.getLong(12))
                                .put("application_name", cursor.getString(13))
                                .put("actions", org.json.JSONArray(cursor.getString(14))))
                        estimatedBytes += row.toString().toByteArray(Charsets.UTF_8).size + 1
                        require(estimatedBytes <= maxBytes - 53)
                        rows.put(row)
                    }
                }
            val result =
                org.json
                    .JSONObject()
                    .put("format", "beacon_history")
                    .put("version", 1)
                    .put("messages", rows)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            require(result.size <= maxBytes - 53)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** Merge atomically; existing messages and local deletion decisions always win. */
    fun importHistory(payload: ByteArray): Int {
        require(payload.size <= maxBytes)
        val text =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(payload))
                .toString()
        val tokenizer = org.json.JSONTokener(text)
        val root = org.json.JSONObject(tokenizer)
        require(tokenizer.nextClean() == '\u0000')
        require(
            root.length() == 3 &&
                root.getString("format") == "beacon_history" &&
                root.get("version") == 1
        )
        val rows = root.getJSONArray("messages")
        require(rows.length() <= 100_000)
        val identities = HashSet<Pair<String, String>>()
        val messages =
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                require(row.length() in 9..15 && row.get("read") is Boolean)
                require(
                    row.keys().asSequence().all {
                        it in
                            setOf(
                                "server",
                                "id",
                                "title",
                                "body",
                                "created",
                                "received",
                                "read",
                                "subtitle",
                                "priority",
                                "bark",
                                "application_id",
                                "application_name",
                                "actions",
                                "group",
                                "expires_at_ms",
                            )
                    }
                )
                for (name in listOf("server", "id", "title", "body", "subtitle")) require(
                    row.get(name) is String
                )
                for (name in listOf("created", "received", "priority")) require(
                    row.get(name) is Int || row.get(name) is Long
                )
                if (row.has("bark")) require(row.get("bark") is String)
                val message =
                    IncomingMessage(
                        row.getString("server"),
                        row.getString("id"),
                        row.getString("title"),
                        row.getString("body"),
                        row.getLong("created"),
                        row.getLong("received"),
                        row.getBoolean("read"),
                        row.getString("subtitle"),
                        row.getInt("priority"),
                        bark = row.optString("bark"),
                        actions = io.github.beacon.app.data.remote.MessageActionsCodec.decode(row),
                        applicationId =
                            io.github.beacon.app.data.remote.MessageMetadataDecoder.application(
                                row
                            ),
                        applicationName =
                            io.github.beacon.app.data.remote.MessageMetadataDecoder.name(row),
                        group = io.github.beacon.app.data.remote.MessageMetadataDecoder.group(row),
                        expiresAtMs =
                            io.github.beacon.app.data.remote.MessageMetadataDecoder.expires(row),
                    )
                io.github.beacon.app.domain.receive.ReceiveMessageValidation.validate(message)
                if (message.bark.isNotEmpty())
                    io.github.beacon.app.data.remote.BarkContent.validate(
                        org.json.JSONObject(message.bark)
                    )
                require(
                    message.createdAtMs >= 0 &&
                        message.receivedAtMs >= 0 &&
                        identities.add(message.server to message.id)
                )
                message
            }
        check(availableBytes() >= 32L * 1024 * 1024)
        val db = helper.writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (message in messages) {
                val deleted =
                    db.rawQuery(
                            "SELECT 1 FROM tombstones WHERE server=? AND id=?",
                            arrayOf(message.server, message.id),
                        )
                        .use { it.moveToFirst() }
                if (deleted) continue
                val values =
                    ContentValues().apply {
                        put("server", message.server)
                        put("id", message.id)
                        put("title", message.title)
                        put("body", message.body)
                        put("source_created", message.createdAtMs)
                        put("received", message.receivedAtMs)
                        put("is_read", if (message.isRead) 1 else 0)
                        put("subtitle", message.subtitle)
                        put("priority", message.priority)
                        put("bark", message.bark)
                        put("application_id", message.applicationId)
                        put("application_name", message.applicationName)
                        put(
                            "actions",
                            io.github.beacon.app.data.remote.MessageActionsCodec.encode(
                                message.actions
                            ),
                        )
                        put("message_group", message.group)
                        put("expires_at", message.expiresAtMs)
                    }
                if (
                    db.insertWithOnConflict(
                        "messages",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L
                )
                    added++
            }
            db.setTransactionSuccessful()
            onImported()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun decryptHistory(secret: Pair<String, String>): Int {
        val db = helper.writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            db.rawQuery(
                    "SELECT server,id FROM messages WHERE bark LIKE '%ciphertext%' AND delete_token=''",
                    null,
                )
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val message = detail(cursor.getString(0), cursor.getString(1)) ?: continue
                        val resolved =
                            io.github.beacon.app.data.remote.BarkContent.resolve(message, secret)
                        if (resolved.bark == message.bark) continue
                        val values =
                            ContentValues().apply {
                                put("title", resolved.title)
                                put("subtitle", resolved.subtitle)
                                put("body", resolved.body)
                                put("bark", resolved.bark)
                            }
                        changed +=
                            db.update(
                                "messages",
                                values,
                                "server=? AND id=?",
                                arrayOf(message.server, message.id),
                            )
                    }
                }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }
}
