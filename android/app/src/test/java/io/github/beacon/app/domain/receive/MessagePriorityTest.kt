package io.github.beacon.app.domain.receive

import org.junit.Assert.*
import org.junit.Test

class MessagePriorityTest {
    @Test
    fun silentMessagesDoNotAlertAndImportantFreshMessagesKeepPriority() {
        val now = 1_000_000L
        val silent = NotificationPolicy.batch(listOf(NotificationIntent(1, now, priority = 2)), now)
        assertFalse(silent.alert)
        val important =
            NotificationPolicy.batch(listOf(NotificationIntent(1, now, priority = 8)), now)
        assertTrue(important.alert)
        assertEquals(8, important.priority)
    }

    @Test
    fun oldImportantMessageDoesNotElevateFreshNormalMessage() {
        val now = 1_000_000L
        val batch =
            NotificationPolicy.batch(
                listOf(
                    NotificationIntent(1, 1, priority = 10),
                    NotificationIntent(2, now, priority = 5),
                ),
                now,
            )
        assertTrue(batch.alert)
        assertEquals(5, batch.priority)
    }
}
