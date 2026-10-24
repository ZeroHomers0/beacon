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
    @Test fun settingsShowClockTodayAndFullDateOnOtherDays() {
        val now = Instant.parse("2026-10-24T04:00:00Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals("08:01:02", DisplayTime.compact(
            Instant.parse("2026-10-24T00:01:02Z").toEpochMilli(), now, zone))
        assertEquals("2026-10-23 08:01:02", DisplayTime.compact(
            Instant.parse("2026-10-23T00:01:02Z").toEpochMilli(), now, zone))
        assertEquals("2026-10-25 08:01:02", DisplayTime.compact(
            Instant.parse("2026-10-25T00:01:02Z").toEpochMilli(), now, zone))
    }

    @Test fun midnightBoundaryUsesLocalDateRatherThanLast24Hours() {
        val now = Instant.parse("2026-10-24T16:00:01Z").toEpochMilli()
        val previous = Instant.parse("2026-10-24T15:59:59Z").toEpochMilli()
        assertEquals("2026-10-24 23:59:59", DisplayTime.compact(previous, now, ZoneId.of("Asia/Shanghai")))
        assertEquals("15:59:59", DisplayTime.compact(previous, now, ZoneOffset.UTC))
    }

    @Test fun cleanupRecordUsesTheSameTodayRule() {
        val now = Instant.parse("2026-10-24T04:00:00Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals("08:01:02 · 已清理 12 条", CleanupResultPresentation.compact(
            "2026-10-24 08:01:02 · 已清理 12 条本地消息", now, zone))
        assertEquals("2026-10-23 08:01:02 · 已清理 12 条", CleanupResultPresentation.compact(
            "2026-10-23 08:01:02 · 已清理 12 条本地消息", now, zone))
    }

}
