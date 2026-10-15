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

    fun format(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatter.withZone(zone).format(Instant.ofEpochMilli(timeMs))
}
