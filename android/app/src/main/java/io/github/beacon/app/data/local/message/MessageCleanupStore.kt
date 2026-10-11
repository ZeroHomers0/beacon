package io.github.beacon.app.data.local.message

import android.database.sqlite.SQLiteOpenHelper
import io.github.beacon.app.data.local.sql.StorageCleanupSql
import io.github.beacon.app.domain.model.CleanupPolicy
import io.github.beacon.app.domain.model.CleanupPreview
import io.github.beacon.app.domain.model.MessageAction
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageMutation
import io.github.beacon.app.domain.model.MessageMutationResult

/** Storage-pressure cleanup: preview, single-use confirmation and the bounded automatic batch. */
internal class MessageCleanupStore(
    private val helper: SQLiteOpenHelper,
    private val databaseFile: java.io.File,
    private val mutate: (MessageMutation) -> MessageMutationResult,
) {

    private data class PreparedCleanup(
        val preview: CleanupPreview,
        val policy: CleanupPolicy,
        val keys: List<MessageKey>,
        val expires: Long,
    )

    private var preparedCleanup: PreparedCleanup? = null

    /** Invalidate any outstanding preview, e.g. when the message set changed underneath it. */
    fun discardPreview() {
        preparedCleanup = null
    }

    fun previewCleanup(policy: CleanupPolicy): CleanupPreview {
        policy.validate()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val sql = StorageCleanupSql
            val counts =
                db.rawQuery(sql.COUNTS, null).use {
                    it.moveToFirst()
                    listOf(it.getLong(0), it.getLong(1), it.getLong(2))
                }
            val cutoff = if (policy.days == 0) 0L else now - policy.days * 86400000L
            val excess = (counts[0] - policy.maxMessages).coerceAtLeast(0)
            val candidateArgs = arrayOf(cutoff.toString(), excess.toString())
            val safe = if (policy.keepUnread) "1" else "0"
            val eligible =
                db.rawQuery(sql.PREVIEW, arrayOf(safe, *candidateArgs)).use {
                    it.moveToFirst()
                    it.getLong(1) to (it.getLong(0) - it.getLong(1))
                }
            val keys =
                db.rawQuery(sql.KEYS, arrayOf(*candidateArgs, safe)).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(
                            MessageKey(cursor.getString(0), cursor.getString(1))
                        )
                    }
                }
            fun pragma(name: String) =
                db.rawQuery("PRAGMA $name", null).use {
                    it.moveToFirst()
                    it.getLong(0)
                }
            val preview =
                CleanupPreview(
                    java.util.UUID.randomUUID().toString(),
                    counts[0],
                    counts[1],
                    counts[2],
                    pragma("page_count") * pragma("page_size"),
                    eligible.first,
                    eligible.second,
                    keys.size,
                    pragma("freelist_count") * pragma("page_size"),
                    db.rawQuery(StorageCleanupSql.PENDING_NOTIFICATIONS, null).use {
                        it.moveToFirst()
                        it.getLong(0)
                    },
                    db.rawQuery("SELECT count(*) FROM tombstones", null).use {
                        it.moveToFirst()
                        it.getLong(0)
                    },
                    java.io.File(databaseFile.path + "-wal").length().coerceAtLeast(0),
                    try {
                        android.os.StatFs(requireNotNull(databaseFile.parent)).availableBytes
                    } catch (_: RuntimeException) {
                        -1L
                    },
                    now,
                )
            db.setTransactionSuccessful()
            preparedCleanup =
                PreparedCleanup(
                    preview,
                    policy,
                    keys,
                    now + CleanupPolicy.PREVIEW_LIFETIME_MS,
                )
            return preview
        } finally {
            db.endTransaction()
        }
    }

    /** Preserve any manual preview while doing a bounded, protected automatic batch. */
    fun automaticCleanup(policy: CleanupPolicy): MessageMutationResult {
        val manual = preparedCleanup
        try {
            val preview = previewCleanup(policy)
            return applyCleanup(preview.token)
        } finally {
            preparedCleanup = manual
        }
    }

    fun applyCleanup(token: String): MessageMutationResult {
        val prepared = requireNotNull(preparedCleanup) { "请重新预览清理范围" }
        val now = System.currentTimeMillis()
        require(
            token == prepared.preview.token &&
                now <= prepared.expires &&
                now >= prepared.expires - CleanupPolicy.PREVIEW_LIFETIME_MS
        ) {
            "清理预览已失效，请重新预览"
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val keys =
                prepared.keys.filter { key ->
                    db.rawQuery(
                            StorageCleanupSql.STILL_SAFE,
                            arrayOf(
                                key.server,
                                key.id,
                                if (prepared.policy.keepUnread) "1" else "0",
                            ),
                        )
                        .use { it.moveToFirst() }
                }
            val result =
                if (keys.isEmpty()) MessageMutationResult(0)
                else mutate(MessageMutation(MessageAction.DELETE, keys))
            db.setTransactionSuccessful()
            preparedCleanup = null
            return result
        } finally {
            db.endTransaction()
        }
    }
}
