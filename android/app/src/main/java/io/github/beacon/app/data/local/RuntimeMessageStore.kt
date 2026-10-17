package io.github.beacon.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.beacon.app.data.local.message.MessageCleanupStore
import io.github.beacon.app.data.local.message.MessageHistoryArchive
import io.github.beacon.app.data.local.message.MessageMutationStore
import io.github.beacon.app.data.local.message.MessageQueryStore
import io.github.beacon.app.data.local.message.MessageReminderStore
import io.github.beacon.app.data.local.message.MessageRetentionStore
import io.github.beacon.app.data.local.message.MessageSubmissionStore
import io.github.beacon.app.domain.model.CleanupPolicy
import io.github.beacon.app.domain.model.CleanupPreview
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageMutation
import io.github.beacon.app.domain.model.MessageMutationResult
import io.github.beacon.app.domain.model.MessagePage
import io.github.beacon.app.domain.model.MessageQuery
import io.github.beacon.app.domain.receive.NotificationIntent
import io.github.beacon.app.domain.receive.NotificationIntentStore
import io.github.beacon.app.domain.receive.SaveResult
import io.github.beacon.app.domain.receive.SyncStore

/** Owned by reception components; UI data requests use the service on a background executor. */
class RuntimeMessageStore(
    context: Context,
    private val availableBytes: () -> Long = {
        android.os.StatFs(context.filesDir.absolutePath).availableBytes
    },
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), SyncStore, NotificationIntentStore {
    init {
        // A server ACK must not outlive a durable local commit after power loss.
        // OpenParams configures every pooled connection; a raw PRAGMA changes only one.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            setOpenParams(
                SQLiteDatabase.OpenParams.Builder()
                    .addOpenFlags(SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                    .setSynchronousMode("FULL")
                    .build()
            )
        } else {
            // Older releases lack a pool-wide synchronous-mode setting. Preserve their
            // rollback journal durability instead of accepting WAL's NORMAL default.
            setWriteAheadLoggingEnabled(false)
        }
    }

    override fun saveBatch(messages: List<IncomingMessage>, shouldContinue: () -> Boolean): Int =
        submissionStore.saveBatch(messages, shouldContinue)

    override fun ackBatchCompleted(server: String, ids: List<String>) =
        submissionStore.ackCompleted(server, ids)

    private val appContext = context.applicationContext
    private val databaseFile = context.getDatabasePath(DATABASE_NAME)

    private val queryStore by lazy { MessageQueryStore(this) }
    private val submissionStore by lazy {
        MessageSubmissionStore(
            this,
            availableBytes,
            { runCatching { TransportSecretStore(appContext).barkSecret() }.getOrNull() },
        )
    }
    private val mutationStore by lazy { MessageMutationStore(this) }
    private val reminderStore by lazy { MessageReminderStore(this) }
    private val retentionStore by lazy { MessageRetentionStore(this) }
    private val cleanupStore by lazy {
        MessageCleanupStore(this, databaseFile) { mutationStore.mutate(it) }
    }
    private val archive by lazy {
        MessageHistoryArchive(
            this,
            availableBytes,
            { server, id -> queryStore.detail(server, id) },
            { cleanupStore.discardPreview() },
            ArchiveCrypto.MAX_BYTES,
        )
    }

    /** Portable history snapshot; no live confirmation queue or credentials are replayed. */
    fun exportHistory(): ByteArray = archive.exportHistory()

    /** Merge atomically; existing messages and local deletion decisions always win. */
    fun importHistory(payload: ByteArray): Int = archive.importHistory(payload)

    fun decryptHistory(secret: Pair<String, String>): Int = archive.decryptHistory(secret)

    fun previewCleanup(policy: CleanupPolicy): CleanupPreview = cleanupStore.previewCleanup(policy)

    fun checkpointWal(): Boolean = cleanupStore.checkpointWal()

    /** Preserve any manual preview while doing a bounded, protected automatic batch. */
    fun automaticCleanup(policy: CleanupPolicy): MessageMutationResult =
        cleanupStore.automaticCleanup(policy)

    fun applyCleanup(token: String): MessageMutationResult = cleanupStore.applyCleanup(token)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(server TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, received INTEGER NOT NULL, source_created INTEGER NOT NULL DEFAULT 0, is_read INTEGER NOT NULL DEFAULT 0, delete_token TEXT NOT NULL DEFAULT '', subtitle TEXT NOT NULL DEFAULT '', priority INTEGER NOT NULL DEFAULT 5, bark TEXT NOT NULL DEFAULT '', application_id TEXT NOT NULL DEFAULT '', application_name TEXT NOT NULL DEFAULT '', message_group TEXT NOT NULL DEFAULT '', expires_at INTEGER NOT NULL DEFAULT 0, actions TEXT NOT NULL DEFAULT '[]', PRIMARY KEY(server,id))"
        )
        db.execSQL("CREATE INDEX messages_received ON messages(received DESC)")
        db.execSQL(
            "CREATE TABLE pending_ack(server TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(server,id))"
        )
        db.execSQL(
            "CREATE TABLE tombstones(server TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(server,id))"
        )
        createCursors(db)
        createNotifications(db)
        createServerIndex(db)
        createDeletionIndex(db)
        createMessageFilterIndexes(db)
        createReceiptTimes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Steps run in ascending order, each guarded by its own target version: the version this
        // file creates is the version it upgrades from, so a bump only has to touch
        // DATABASE_VERSION and append one guarded step below.
        check(newVersion == DATABASE_VERSION && oldVersion in 1 until DATABASE_VERSION)
        if (oldVersion < 2) createCursors(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN source_created INTEGER NOT NULL DEFAULT 0")
            createNotifications(db)
        }
        if (oldVersion < 4) createServerIndex(db)
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN is_read INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN delete_token TEXT NOT NULL DEFAULT ''")
            createDeletionIndex(db)
        }
        if (oldVersion < 7)
            db.execSQL("ALTER TABLE messages ADD COLUMN subtitle TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 10)
            db.execSQL("ALTER TABLE messages ADD COLUMN priority INTEGER NOT NULL DEFAULT 5")
        if (oldVersion < 12)
            db.execSQL("ALTER TABLE messages ADD COLUMN bark TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 13) {
            db.execSQL("ALTER TABLE messages ADD COLUMN application_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN message_group TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN application_name TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 14)
            db.execSQL("ALTER TABLE messages ADD COLUMN actions TEXT NOT NULL DEFAULT '[]'")
        if (oldVersion < 15) createMessageFilterIndexes(db)
        // Idempotent repairs that have always run on every upgrade; they must follow the column
        // additions above because they read `delete_token` and `source_created`.
        repairLegacyTables(db)
    }

    /**
     * Idempotent repairs every upgrade performs: drop the dead undo tables, move revoked rows into
     * tombstones, and backfill receipt times. Safe to rerun, which is why it is not version-gated.
     */
    private fun repairLegacyTables(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS source_preferences")
        db.execSQL("DROP TABLE IF EXISTS undo_batches")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS tombstones(server TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY(server,id))"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO tombstones(server,id) SELECT server,id FROM messages WHERE delete_token<>''"
        )
        db.execSQL(
            "DELETE FROM notification_intents WHERE EXISTS(SELECT 1 FROM messages m WHERE m.server=notification_intents.server AND m.id=notification_intents.id AND m.delete_token<>'')"
        )
        db.execSQL("DELETE FROM messages WHERE delete_token<>''")
        createReceiptTimes(db)
        db.execSQL(
            "INSERT OR IGNORE INTO receipt_times SELECT p.server,p.id,m.source_created,0 FROM pending_ack p JOIN messages m ON m.server=p.server AND m.id=p.id WHERE m.source_created>0"
        )
    }

    override fun save(incoming: IncomingMessage): SaveResult = submissionStore.save(incoming)

    override fun ackCompleted(server: String, id: String) = submissionStore.ackCompleted(server, id)

    /**
     * Confirmed revocation makes these receipts impossible to submit. Keep history and reminders.
     */
    fun retireRevokedReceipts(namespace: String) = submissionStore.retireRevokedReceipts(namespace)

    fun recent(): List<IncomingMessage> = queryStore.recent()

    fun page(query: MessageQuery): MessagePage = queryStore.page(query)

    fun detail(server: String, id: String): IncomingMessage? = queryStore.detail(server, id)

    fun remove(server: String, id: String) = mutationStore.remove(server, id)

    /** Explicit destructive action; preserve cursors, pending confirmations and deduplication. */
    fun markAllRead(): Long = mutationStore.markAllRead()

    fun clearAllMessages(): Long = mutationStore.clearAllMessages()

    fun pendingReminderCount(): Long = queryStore.pendingReminderCount()

    fun mutate(request: MessageMutation): MessageMutationResult = mutationStore.mutate(request)

    /** A visible notification was handled: mark read and consume its reminder atomically. */
    fun readFromNotification(key: MessageKey): Int = reminderStore.readFromNotification(key)

    /**
     * A summary notification was handled: every message it stands for is read in one transaction.
     */
    fun readFromNotification(keys: List<MessageKey>): Int = reminderStore.readFromNotification(keys)

    private fun createCursors(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sync_cursors(server TEXT PRIMARY KEY, sequence INTEGER NOT NULL)")
    }

    private fun createReceiptTimes(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS retention_contracts(server TEXT PRIMARY KEY)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS receipt_times(server TEXT NOT NULL,id TEXT NOT NULL,created INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(server,id))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS receipt_times_expiry ON receipt_times(server,created)"
        )
    }

    /** Called only with an authenticated server's supported maximum redelivery contract. */
    override fun retireExpiredRecords(server: String, beforeMs: Long): Int =
        retentionStore.retireExpiredRecords(server, beforeMs)

    override fun requiresRetentionContract(server: String): Boolean =
        retentionStore.requiresRetentionContract(server)

    private fun createServerIndex(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX messages_server ON messages(server)")
    }

    private fun createDeletionIndex(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX messages_delete_token ON messages(delete_token)")
    }

    /**
     * Indexes for the chip row and the read-state filter.
     *
     * The list query is `delete_token='' AND rowid < ? ORDER BY rowid DESC`, which the primary
     * key already serves. These cover the two filters that would otherwise degrade into a scan
     * of the whole table once the history is full.
     */
    private fun createMessageFilterIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_group ON messages(message_group)")
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_application ON messages(application_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_unread ON messages(is_read)")
    }

    private fun createNotifications(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notification_intents(seq INTEGER PRIMARY KEY AUTOINCREMENT, server TEXT NOT NULL, id TEXT NOT NULL, UNIQUE(server,id))"
        )
    }

    override fun pendingNotifications(): List<NotificationIntent> =
        reminderStore.pendingNotifications()

    /** Durable single-message reminder; never changes unread state or receive confirmations. */
    fun requestReminder(key: MessageKey): NotificationIntent? = reminderStore.requestReminder(key)

    override fun notificationsPosted(intents: List<NotificationIntent>) =
        reminderStore.notificationsPosted(intents)

    override fun cursor(server: String): Long = queryStore.cursor(server)

    override fun advanceCursor(server: String, sequence: Long) =
        queryStore.advanceCursor(server, sequence)

    override fun pendingIds(server: String): List<String> = queryStore.pendingIds(server)

    companion object {
        const val DATABASE_NAME = "beacon-messages.db"
        // Bump this and add the matching step in onUpgrade; nothing else names the version.
        const val DATABASE_VERSION = 15
    }
}
