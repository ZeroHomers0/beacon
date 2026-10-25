package io.github.beacon.app.domain.policy

/** Schedule only the next displayed minute boundary or expiry, never a one-second loop. */
object CountdownDisplayPolicy {
    fun minutes(target: Long, now: Long): Long =
        ((target - now).coerceAtLeast(0L) + 59_999L) / 60_000L

    fun nextDelay(target: Long, now: Long): Long? {
        val remaining = target - now
        if (remaining <= 0) return null
        return (remaining % 60_000L).takeIf { it > 0 } ?: 60_000L
    }
}
