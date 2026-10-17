package io.github.beacon.app.runtime

import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.receive.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RuntimeNotificationsTest {
    private val now = 10_000_000L

    private class Store(time: Long) : NotificationIntentStore {
        var pending = listOf(NotificationIntent(1, time))
        override fun pendingNotifications() = pending
        override fun notificationsPosted(intents: List<NotificationIntent>) {
            pending = pending.filterNot { it in intents }
        }
    }

    @Test
    fun deniedPermissionDoesNotDeferSaveOnlyRemindersUntilPermissionReturns() {
        for (quiet in listOf(false, true)) {
            val store = Store(if (quiet) now else 0)
            val settings = NotificationSettings(outdatedReminder = OutdatedReminder.SAVE_ONLY)
            val quietHours = QuietHours(quiet, 0, 600, behavior = QuietHoursBehavior.SAVE_ONLY)
            val result = flushPendingNotifications(
                store, settings, quietHours, now,
                { error("Suppressed reminders must not reach permission checks") },
                { error("Suppressed reminders must not be posted") },
                { ZoneId.of("UTC") },
            )
            // Both the quiet-hours branch and the outdated-reminder branch report a save-only
            // outcome; the durable proof is that nothing stays pending.
            assertTrue(result, result.contains("保存"))
            assertTrue(store.pending.isEmpty())
            assertEquals("", flushPendingNotifications(
                store, settings, QuietHours(), now + 60_000, { true },
                { error("Permission restoration must not replay suppressed reminders") },
            ))
        }
    }

    @Test
    fun deniedPermissionPreservesEligibleReminderWithoutConstructingNotification() {
        val store = Store(now)
        // A denied permission must not consume the intent; the text only needs to say it will be
        // retried, and the intent itself is the durable proof.
        assertTrue(flushPendingNotifications(
            store, NotificationSettings(), QuietHours(), now, { false },
            { error("No notification construction when permission is denied") },
        ).contains("稍后重试"))
        assertEquals(1, store.pending.size)
        var posts = 0
        flushPendingNotifications(
            store, NotificationSettings(), QuietHours(), now + 1, { true },
            { posts++; true },
        )
        assertEquals(1, posts)
        assertTrue(store.pending.isEmpty())
    }
}
