package io.github.beacon.app.domain.model

import java.time.Instant
import java.time.ZoneId

enum class QuietHoursBehavior {
    SILENT,
    SAVE_ONLY,
}

/** Selected weekdays belong to the day on which a quiet interval starts. */
data class QuietHours(
    val enabled: Boolean = false,
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 8 * 60,
    val weekdays: Int = 127,
    val behavior: QuietHoursBehavior = QuietHoursBehavior.SILENT,
) {
    fun validate() {
        require(startMinute in 0..1439 && endMinute in 0..1439)
        require(startMinute != endMinute)
        require(weekdays in 1..127)
    }

    fun active(nowMs: Long, zone: ZoneId): Boolean {
        validate()
        if (!enabled) return false
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val minute = local.hour * 60 + local.minute
        val day = local.toLocalDate()
        val startDay =
            when {
                startMinute < endMinute -> {
                    if (minute !in startMinute until endMinute) return false
                    day
                }
                minute >= startMinute -> day
                minute < endMinute -> day.minusDays(1)
                else -> return false
            }
        return weekdays and (1 shl (startDay.dayOfWeek.value - 1)) != 0
    }
}
