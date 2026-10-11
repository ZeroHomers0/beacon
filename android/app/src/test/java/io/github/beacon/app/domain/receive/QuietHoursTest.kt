package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.QuietHours
import io.github.beacon.app.domain.model.QuietHoursBehavior
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class QuietHoursTest {
    private val utc = ZoneId.of("UTC")

    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    @Test
    fun overnightWeekdayBelongsToStartDayAndEndIsExclusive() {
        val friday = QuietHours(enabled = true, weekdays = 1 shl 4)
        assertFalse(friday.active(time("2026-10-09T21:59:59Z"), utc))
        assertTrue(friday.active(time("2026-10-09T22:00:00Z"), utc))
        assertTrue(friday.active(time("2026-10-10T07:59:59Z"), utc))
        assertFalse(friday.active(time("2026-10-10T08:00:00Z"), utc))
        assertFalse(friday.active(time("2026-10-10T22:00:00Z"), utc))
    }

    @Test
    fun daytimeAndSundayRolloverAreBounded() {
        val day = QuietHours(true, 600, 660)
        assertTrue(day.active(time("2026-10-09T10:00:00Z"), utc))
        assertFalse(day.active(time("2026-10-09T11:00:00Z"), utc))
        val sunday = QuietHours(enabled = true, weekdays = 1 shl 6)
        assertTrue(sunday.active(time("2026-10-12T07:00:00Z"), utc))
        assertFalse(sunday.active(time("2026-10-12T22:00:00Z"), utc))
    }

    @Test
    fun currentZoneAndDaylightSavingAreEvaluatedAtDelivery() {
        val policy = QuietHours(true, 60, 180)
        val now = time("2026-11-01T06:30:00Z")
        assertTrue(policy.active(now, ZoneId.of("America/New_York")))
        assertFalse(policy.active(now, utc))
        assertTrue(policy.active(time("2026-11-01T05:30:00Z"), ZoneId.of("America/New_York")))
        assertFalse(QuietHours().active(now, utc))
    }

    @Test
    fun invalidSchedulesAreRejected() {
        for (invalid in
            listOf(
                QuietHours(startMinute = -1),
                QuietHours(endMinute = 1440),
                QuietHours(endMinute = 1320),
                QuietHours(weekdays = 0),
                QuietHours(weekdays = 128),
            )) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        }
    }

    private class Store : NotificationIntentStore {
        var pending = listOf(NotificationIntent(1, timeMs, "title", "body"))
        var fail = false

        override fun pendingNotifications() = pending

        override fun notificationsPosted(intents: List<NotificationIntent>) {
            if (fail) throw java.io.IOException("disk full")
            pending = pending.filterNot { it in intents }
        }
    }

    @Test
    fun silentPostsWithoutAlertAndSaveOnlyConsumesWithoutPosting() {
        val store = Store()
        val silent = QuietHours(enabled = true)
        var batch: NotificationBatch? = null
        NotificationPipeline(
                store,
                {
                    batch = it
                    true
                },
                silent,
                { utc },
            )
            .flush(timeMs)
        assertFalse(batch!!.alert)
        assertEquals("title", batch!!.title)
        assertTrue(store.pending.isEmpty())
        val only = Store()
        val pipeline =
            NotificationPipeline(
                only,
                { error("Must not notify") },
                silent.copy(behavior = QuietHoursBehavior.SAVE_ONLY),
                { utc },
            )
        assertTrue(pipeline.flush(timeMs).contains("仅保存"))
        assertEquals("", pipeline.flush(time("2026-10-10T09:00:00Z")))
    }

    @Test
    fun failedNotificationOrDispositionCommitPreservesIntents() {
        val store = Store()
        val silent = QuietHours(enabled = true)
        NotificationPipeline(store, { false }, silent, { utc }).flush(timeMs)
        assertEquals(1, store.pending.size)
        store.fail = true
        assertThrows(java.io.IOException::class.java) {
            NotificationPipeline(
                    store,
                    { error("Must not notify") },
                    silent.copy(behavior = QuietHoursBehavior.SAVE_ONLY),
                    { utc },
                )
                .flush(timeMs)
        }
        assertEquals(1, store.pending.size)
    }

    companion object {
        private val timeMs = Instant.parse("2026-10-09T23:00:00Z").toEpochMilli()
    }
}
