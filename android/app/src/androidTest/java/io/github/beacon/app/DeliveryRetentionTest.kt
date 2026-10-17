package io.github.beacon.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.IncomingMessage
import org.junit.Assert.*
import org.junit.Test

class DeliveryRetentionTest {
    @Test
    fun scopedReclamationPreservesUnknownAgesFreshReceiptsAndHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.util.UUID.randomUUID().toString().toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        val now = System.currentTimeMillis()
        RuntimeMessageStore(context).use { store ->
            try {
                store.save(
                    IncomingMessage(
                        namespace,
                        "old",
                        "",
                        "body",
                        now - 40 * 86400000L,
                        priority = 0,
                    )
                )
                store.save(IncomingMessage(namespace, "fresh", "", "body", now, priority = 0))
                store.save(IncomingMessage(namespace, "unknown", "", "body", priority = 0))
                store.remove(namespace, "old")
                store.remove(namespace, "unknown")
                assertFalse(store.requiresRetentionContract(namespace))
                assertEquals(
                    1,
                    store.retireExpiredRecords(namespace, now - 37 * 86400000L - 600000L),
                )
                assertTrue(store.requiresRetentionContract(namespace))
                assertEquals(setOf("fresh", "unknown"), store.pendingIds(namespace).toSet())
                assertNotNull(store.detail(namespace, "fresh"))
                assertNull(store.detail(namespace, "old"))
                val db = store.readableDatabase
                db.rawQuery("SELECT id FROM tombstones WHERE server=?", arrayOf(namespace)).use {
                    cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("unknown", cursor.getString(0))
                    assertFalse(cursor.moveToNext())
                }
                assertEquals(
                    0,
                    store.retireExpiredRecords(namespace, now - 37 * 86400000L - 600000L),
                )
            } finally {
                listOf(
                        "messages",
                        "pending_ack",
                        "tombstones",
                        "receipt_times",
                        "retention_contracts",
                    )
                    .forEach { store.writableDatabase.delete(it, "server=?", arrayOf(namespace)) }
            }
        }
    }
}
