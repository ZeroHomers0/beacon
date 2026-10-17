package io.github.beacon.app.domain.policy

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DisplayTimeTest {
    @Test fun messageCardClockUsesOnlyHoursMinutesAndSeconds() {
        val time = Instant.parse("2026-10-15T00:01:02Z").toEpochMilli()
        assertEquals("08:01:02", DisplayTime.clock(time, ZoneId.of("Asia/Shanghai")))
        assertEquals("00:01:02", DisplayTime.clock(time, ZoneOffset.UTC))
    }
    @Test fun numericTimestampIncludesSecondsAndRespectsZone() {
        val time = Instant.parse("2026-10-15T00:01:02.999Z").toEpochMilli()
        assertEquals("2026-10-15 08:01:02", DisplayTime.format(time, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-10-15 00:01:02", DisplayTime.format(time, ZoneOffset.UTC))
    }
    @Test fun localeDoesNotChangeDigitsOrCalendar() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH"))
            assertEquals("1970-01-01 00:00:00", DisplayTime.format(0, ZoneOffset.UTC))
        } finally { Locale.setDefault(original) }
    }
    @Test fun cleanupKeepsStandardTimestampAndDropsRedundantNoun() {
        assertEquals("2026-10-15 08:01:02 · 已清理 12 条", CleanupResultPresentation.compact("2026-10-15 08:01:02 · 已清理 12 条本地消息"))
    }
    @Test fun legacyLocalizedCleanupRetainsCountWithoutInventingTime() {
        assertEquals("已清理 0 条", CleanupResultPresentation.compact("2026年10月15日 上午8:01:02 · 已清理 0 条本地消息"))
    }
    @Test fun cleanupFailureRemainsVisible() {
        val error = "自动清理未完成；下次成功接收后重试"
        assertEquals(error, CleanupResultPresentation.compact(error))
    }
    @Test fun compactCleanupResultIsStable() {
        val result = "2026-10-15 08:01:02 · 已清理 12 条"
        assertEquals(result, CleanupResultPresentation.compact(result))
    }
}
