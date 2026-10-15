package io.github.beacon.app.domain.policy

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared numeric timestamp format; exports explicitly select UTC. */
object DisplayTime {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun format(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatter.withZone(zone).format(Instant.ofEpochMilli(timeMs))
}
