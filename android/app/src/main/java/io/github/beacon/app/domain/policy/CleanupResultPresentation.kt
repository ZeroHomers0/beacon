package io.github.beacon.app.domain.policy

import java.time.LocalDateTime
import java.time.ZoneId

/** Old localized timestamps cannot always be recovered; retain their count without guessing a date. */
object CleanupResultPresentation {
    private val cleaned = Regex("^(?:(.*?) · )?已清理 (\\d+) 条(?:本地消息)?$")
    private val timestamp = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")

    fun compact(
        value: String,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val match = cleaned.matchEntire(value) ?: return value
        val time = match.groupValues[1].takeIf { timestamp.matches(it) }
        val result = "已清理 ${match.groupValues[2]} 条"
        if (time == null) return result
        val displayTime = runCatching {
            val recorded = LocalDateTime.parse(time.replace(' ', 'T')).atZone(zone).toInstant()
            DisplayTime.compact(recorded.toEpochMilli(), nowMs, zone)
        }.getOrDefault(time)
        return "$displayTime · $result"
    }
}
