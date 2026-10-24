package io.github.beacon.app.domain.policy

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared numeric timestamp format; exports explicitly select UTC. */
object DisplayTime {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    fun clock(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFormatter.withZone(zone).format(Instant.ofEpochMilli(timeMs))

    /** Local calendar dates, rather than elapsed hours, decide whether the date is needed. */
    fun compact(
        timeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (date == today) clock(timeMs, zone) else format(timeMs, zone)
    }

    fun format(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatter.withZone(zone).format(Instant.ofEpochMilli(timeMs))
}
