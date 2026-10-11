package io.github.beacon.app.feature.messages

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTimeTest {
    @Test
    fun deviceZoneKeepsSecondsWithoutPrintingOffset() {
        val timestamp = Instant.parse("2026-10-09T00:01:02Z").toEpochMilli()
        assertEquals("2026-10-09 08:01:02", detailTime(timestamp, "缺失", ZoneId.of("Asia/Shanghai")))
        assertEquals(
            "2026-10-08 20:01:02",
            detailTime(timestamp, "缺失", ZoneId.of("America/New_York")),
        )
        assertEquals("缺失", detailTime(0, "缺失"))
    }
}
