package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class NotificationSettingsTest {
    private val now = 10_000_000L

    private class Store(val time: Long) : NotificationIntentStore {
        var pending = listOf(NotificationIntent(1, time, "title", "body"))
        var fail = false

        override fun pendingNotifications() = pending

        override fun notificationsPosted(intents: List<NotificationIntent>) {
            if (fail) throw java.io.IOException("storage")
            pending = pending.filterNot { it in intents }
        }
    }

    @Test
    fun freshnessUsesConfiguredWindowButNeverAlertsOnUnknownOrFutureTime() {
        val old = listOf(NotificationIntent(1, now - 600_000))
        assertFalse(NotificationPolicy.batch(old, now, NotificationSettings().freshWindowMs).alert)
        assertTrue(NotificationPolicy.batch(old, now, NotificationSettings(15).freshWindowMs).alert)
        for (time in listOf(0L, -1L, now + 1)) {
            assertFalse(
                NotificationPolicy.batch(listOf(NotificationIntent(1, time)), now, 86_400_000).alert
            )
        }
        assertTrue(
            NotificationPolicy.batch(listOf(NotificationIntent(1, now - 60_000)), now, 60_000).alert
        )
        assertFalse(
            NotificationPolicy.batch(listOf(NotificationIntent(1, now - 60_001)), now, 60_000).alert
        )
    }

    @Test
    fun outdatedSaveOnlyConsumesReminderAndDoesNotDeferIt() {
        val store = Store(0)
        val pipeline =
            NotificationPipeline(
                store,
                { error("Must not notify") },
                QuietHours(),
                settings = NotificationSettings(outdatedReminder = OutdatedReminder.SAVE_ONLY),
            )
        val result = pipeline.flush(now)
        // An outdated reminder under SAVE_ONLY is consumed, not deferred: the only feedback is
        // that the message was stored, so it cannot reappear as a notification later.
        assertTrue(result, result.contains("无需显示通知"))
        assertTrue(store.pending.isEmpty())
        assertEquals("", pipeline.flush(now + 60_000))
    }

    @Test
    fun recentMixedBatchStillRemindsAndQuietHoursCanSuppressIt() {
        val mixed =
            NotificationPolicy.batch(
                listOf(NotificationIntent(1, 0), NotificationIntent(2, now)),
                now,
            )
        assertTrue(mixed.alert)
        val fresh = Store(now)
        var posted = false
        NotificationPipeline(
                fresh,
                {
                    posted = it.alert
                    true
                },
                QuietHours(),
                settings = NotificationSettings(outdatedReminder = OutdatedReminder.SAVE_ONLY),
            )
            .flush(now)
        assertTrue(posted)
        val quiet = QuietHours(true, 0, 600, behavior = QuietHoursBehavior.SAVE_ONLY)
        val suppressed = Store(now)
        NotificationPipeline(
                suppressed,
                { error("Must not notify") },
                quiet,
                { java.time.ZoneId.of("UTC") },
            )
            .flush(now)
        assertTrue(suppressed.pending.isEmpty())
    }

    @Test
    fun storageFailurePreservesSuppressedReminderAndInvalidSettingsAreRejected() {
        val store = Store(0).apply { fail = true }
        assertThrows(java.io.IOException::class.java) {
            NotificationPipeline(
                    store,
                    { error("Must not notify") },
                    QuietHours(),
                    settings = NotificationSettings(outdatedReminder = OutdatedReminder.SAVE_ONLY),
                )
                .flush(now)
        }
        assertEquals(1, store.pending.size)
        assertThrows(IllegalArgumentException::class.java) { NotificationSettings(0).validate() }
        assertThrows(IllegalArgumentException::class.java) { NotificationSettings(1441).validate() }
        // The lockscreen-preview switch was removed; only the freshness window remains.
        assertEquals(5 * 60_000L, NotificationSettings().freshWindowMs)
    }
}
