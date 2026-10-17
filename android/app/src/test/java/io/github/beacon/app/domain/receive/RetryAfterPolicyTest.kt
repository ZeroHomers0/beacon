package io.github.beacon.app.domain.receive

import org.junit.Assert.*
import org.junit.Test

class RetryAfterPolicyTest {
    @Test
    fun parsesSecondsAndHttpDatesWithBoundedWaits() {
        val now = java.time.Instant.parse("2026-10-13T00:00:00Z").toEpochMilli()
        assertEquals(120_000L, RetryAfterPolicy.delayMs(" 120 ", now))
        assertEquals(120_000L, RetryAfterPolicy.delayMs("Tue, 13 Oct 2026 00:02:00 GMT", now))
        assertEquals(0L, RetryAfterPolicy.delayMs("Mon, 12 Oct 2026 00:02:00 GMT", now))
        assertEquals(
            RetryAfterPolicy.MAX_DELAY_MS,
            RetryAfterPolicy.delayMs("999999999999999999999999", now),
        )
        for (value in listOf(null, "", "-1", "1.5", "invalid")) assertNull(
            RetryAfterPolicy.delayMs(value, now)
        )
    }

    @Test
    fun findsCooldownInWrappedTransportFailures() {
        val error = HttpTransientException(429, SyncStage.CAPABILITIES, 120_000)
        assertEquals(120_000L, RetryAfterPolicy.from(java.io.IOException(error)))
        assertNull(RetryAfterPolicy.from(java.io.IOException("offline")))
    }
}
