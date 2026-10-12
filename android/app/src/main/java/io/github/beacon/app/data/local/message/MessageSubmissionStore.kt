package io.github.beacon.app.data.local.message

import android.content.ContentValues
import android.database.sqlite.SQLiteOpenHelper
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.receive.SaveResult

/** Reception-side writes: saving incoming messages and receive-confirmation bookkeeping. */
internal class MessageSubmissionStore(
    private val helper: SQLiteOpenHelper,
    private val availableBytes: () -> Long,
    private val barkSecret: () -> Pair<String, String>?,
) {

    fun save(incoming: IncomingMessage): SaveResult {
        val message =
            io.github.beacon.app.data.remote.BarkContent.resolve(
                incoming,
                runCatching { barkSecret() }.getOrNull(),
            )
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val deleted = rowExists(db, "tombstones", message.server, message.id)
            val values =
                ContentValues().apply {
                    put("server", message.server)
                    put("id", message.id)
                    put("title", message.title)
                    put("body", message.body)
                    put("received", System.currentTimeMillis())
                    put("source_created", message.createdAtMs)
                    put("subtitle", message.subtitle)
                    put("priority", message.priority)
                    put("bark", message.bark)
                    put("application_id", message.applicationId)
                    put("application_name", message.applicationName)
                    put("message_group", message.group)
                    put("expires_at", message.expiresAtMs)
                }
            val result =
                when {
                    deleted -> SaveResult.DELETED
                    rowExists(db, "messages", message.server, message.id) -> SaveResult.DUPLICATE
                    else -> {
                        if (
                            !io.github.beacon.app.domain.policy.StoragePressurePolicy
                                .allowsNewMessage(availableBytes())
                        ) {
                            throw android.database.sqlite.SQLiteFullException(
                                "Reception storage reserve reached"
                            )
                        }
                        db.insertOrThrow("messages", null, values)
                        SaveResult.INSERTED
                    }
                }
            db.execSQL(
                "INSERT OR IGNORE INTO pending_ack(server,id) VALUES(?,?)",
                arrayOf(message.server, message.id),
            )
            if (message.createdAtMs > 0)
                db.execSQL(
                    "INSERT OR IGNORE INTO receipt_times(server,id,created,deleted) VALUES(?,?,?,0)",
                    arrayOf<Any>(message.server, message.id, message.createdAtMs),
                )
            if (
                result == SaveResult.INSERTED &&
                    message.server != "diagnostic-local" &&
                    message.priority > 0
            ) {
                db.execSQL(
                    "INSERT INTO notification_intents(server,id) VALUES(?,?)",
                    arrayOf(message.server, message.id),
                )
            }
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun ackCompleted(server: String, id: String) = ackCompleted(server, listOf(id))

    /**
     * Both tables are retired for the whole set in one commit. A per-message call issued two
     * separate deletions, i.e. two implicit transactions each; a 100-message catch-up therefore
     * paid ~200 commits for bookkeeping that belongs together.
     */
    fun ackCompleted(server: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.delete("pending_ack", "server=? AND id=?", arrayOf(server, id))
                db.delete("receipt_times", "server=? AND id=? AND deleted=0", arrayOf(server, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Confirmed revocation makes these receipts impossible to submit. Keep history and reminders.
     */
    fun retireRevokedReceipts(namespace: String) {
        require(namespace.matches(Regex("[0-9a-f]{64}")))
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("pending_ack", "server=?", arrayOf(namespace))
            db.delete("receipt_times", "server=? AND deleted=0", arrayOf(namespace))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
