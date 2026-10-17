package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiveDeadlinePolicyTest {
    private val rules = ReceivePlan(mode = ReceiveMode.RULES, scheduleEnabled = true)

    private fun now(text: String) = ZonedDateTime.parse(text)

    @Test
    fun healthyConnectionAndUnscheduledRulesNeedNoTimer() {
        val time = now("2026-10-10T12:00:00+08:00[Asia/Shanghai]")
        assertNull(ReceiveDeadlinePolicy.delayMs(ReceivePlan(mode = ReceiveMode.REALTIME), time, 0))
        assertNull(ReceiveDeadlinePolicy.delayMs(rules.copy(scheduleEnabled = false), time, 0))
    }

    @Test
    fun retriesUseTheirActualDeadlineAndExpiredRetriesDoNotSpin() {
        val time = now("2026-10-10T12:00:00+08:00[Asia/Shanghai]")
        val plan = ReceivePlan(mode = ReceiveMode.REALTIME)
        assertEquals(
            1200L,
            ReceiveDeadlinePolicy.delayMs(plan, time, time.toInstant().toEpochMilli() + 1200),
        )
        assertNull(ReceiveDeadlinePolicy.delayMs(plan, time, time.toInstant().toEpochMilli()))
    }

    @Test
    fun boundaryAndRetryChooseTheEarlierDeadline() {
        val time = now("2026-10-10T08:59:40+08:00[Asia/Shanghai]")
        assertEquals(20000L, ReceiveDeadlinePolicy.delayMs(rules, time, 0))
        assertEquals(
            5000L,
            ReceiveDeadlinePolicy.delayMs(rules, time, time.toInstant().toEpochMilli() + 5000),
        )
    }

    @Test
    fun exactBoundaryAdvancesToNextBoundaryRatherThanLooping() {
        val time = now("2026-10-10T09:00:00+08:00[Asia/Shanghai]")
        assertEquals(9 * 60 * 60 * 1000L, ReceiveDeadlinePolicy.delayMs(rules, time, 0))
    }

    @Test
    fun midnightWindowSchedulesTomorrowWhenNecessary() {
        val plan = rules.copy(scheduleStart = 22 * 60, scheduleEnd = 8 * 60)
        assertEquals(
            8 * 60 * 60 * 1000L,
            ReceiveDeadlinePolicy.delayMs(plan, now("2026-10-10T00:00:00+08:00[Asia/Shanghai]"), 0),
        )
        assertEquals(
            9 * 60 * 60 * 1000L,
            ReceiveDeadlinePolicy.delayMs(plan, now("2026-10-10T23:00:00+08:00[Asia/Shanghai]"), 0),
        )
    }

    @Test
    fun skippedAndRepeatedHoursRecomputeAtOffsetChanges() {
        val plan = rules.copy(scheduleStart = 90, scheduleEnd = 150)
        assertEquals(
            60000L,
            ReceiveDeadlinePolicy.delayMs(
                plan,
                now("2026-03-08T01:59:00-05:00[America/New_York]"),
                0,
            ),
        )
        assertEquals(
            60000L,
            ReceiveDeadlinePolicy.delayMs(
                plan,
                now("2026-11-01T01:59:00-04:00[America/New_York]"),
                0,
            ),
        )
        assertEquals(
            30 * 60 * 1000L,
            ReceiveDeadlinePolicy.delayMs(
                plan,
                now("2026-11-01T01:00:00-05:00[America/New_York]"),
                0,
            ),
        )
    }
}
