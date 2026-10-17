package io.github.beacon.app

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class StorageMaintenanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > end) fail("Timed out: $label")
            Thread.sleep(20)
        }
    }

    @Test fun truncatePreservesMessagesReceiptsNotificationsAndDeduplication() {
        val base = instrumentation.targetContext
        val prefix = "wal-test-${java.util.UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = base.getDatabasePath(prefix + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                base.openOrCreateDatabase(prefix + name, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) =
                base.openOrCreateDatabase(prefix + name, mode, factory, handler)
        }
        try {
            RuntimeMessageStore(context).use { store ->
                repeat(8) { store.save(IncomingMessage("wal", "$it", "synthetic", "body ".repeat(1000))) }
                store.writableDatabase.execSQL("INSERT INTO tombstones(server,id) VALUES('wal','deleted')")
                val before = store.previewCleanup(CleanupPolicy())
                val messages = store.page(MessageQuery()).items
                val receipts = store.pendingIds("wal")
                val notices = store.pendingNotifications()
                assertTrue(before.walBytes > 0)
                assertTrue(store.checkpointWal())
                assertEquals(0L, java.io.File(context.getDatabasePath("beacon-messages.db").path + "-wal").length())
                assertEquals(messages, store.page(MessageQuery()).items)
                assertEquals(receipts, store.pendingIds("wal"))
                assertEquals(notices, store.pendingNotifications())
                val after = store.previewCleanup(CleanupPolicy())
                assertEquals(before.total, after.total)
                assertEquals(before.pending, after.pending)
                assertEquals(before.pendingNotifications, after.pendingNotifications)
                assertEquals(before.tombstones, after.tombstones)
                store.writableDatabase.rawQuery("PRAGMA integrity_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
                assertNull(store.detail("wal", "deleted"))
            }
        } finally { base.deleteDatabase(prefix + "beacon-messages.db") }
    }

    @Test fun serviceCheckpointRefreshesUsageWithoutDeletingMessages() {
        lateinit var repository: RuntimeRepository
        main { repository = RuntimeRepository().also { it.start(instrumentation.targetContext) } }
        try {
            await("connected") { repository.state.value.receivePlanLoaded && !repository.state.value.messageBusy }
            main { repository.previewCleanup(CleanupPolicy()) }
            await("preview") { repository.state.value.cleanupPreview != null && !repository.state.value.messageBusy }
            val before = repository.state.value.cleanupPreview!!
            main { repository.checkpointStorage() }
            await("checkpoint refreshed") {
                !repository.state.value.messageBusy && repository.state.value.cleanupPreview?.token?.let { it != before.token } == true
            }
            val after = repository.state.value.cleanupPreview!!
            assertEquals(before.total, after.total)
            assertEquals(before.pending, after.pending)
            assertEquals(before.pendingNotifications, after.pendingNotifications)
            assertTrue(repository.state.value.status.text.contains("临时日志已清理"))
        } finally { main { repository.close() } }
    }

    @Test fun combinedReceptionSaveConfirmsBothHalvesAndCanRestoreThem() {
        lateinit var repository: RuntimeRepository
        main { repository = RuntimeRepository().also { it.start(instrumentation.targetContext) } }
        await("connected") { repository.state.value.receivePlanLoaded && !repository.state.value.messageBusy }
        val originalPlan = repository.state.value.receivePlan
        val originalPolling = repository.state.value.pollingState.config
        try {
            val targetPlan = originalPlan.copy(heartbeatSeconds = if (originalPlan.heartbeatSeconds == 120) 60 else 120)
            val targetPolling = originalPolling.copy(intervalMinutes = if (originalPolling.intervalMinutes == 30) 60 else 30)
            main {
                repository.saveReception(targetPlan, targetPolling)
                assertTrue(repository.state.value.messageBusy)
            }
            await("both halves confirmed") {
                !repository.state.value.messageBusy && repository.state.value.receivePlan == targetPlan && repository.state.value.pollingState.config == targetPolling
            }
        } finally {
            main { repository.saveReception(originalPlan, originalPolling) }
            await("restored") {
                !repository.state.value.messageBusy && repository.state.value.receivePlan == originalPlan && repository.state.value.pollingState.config == originalPolling
            }
            main { repository.close() }
        }
    }
}
