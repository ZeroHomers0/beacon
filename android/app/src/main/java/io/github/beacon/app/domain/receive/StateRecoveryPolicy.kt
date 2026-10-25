package io.github.beacon.app.domain.receive

/** Monotonic, bounded safety checks; actual change signals always bypass this gate. */
class StateRecoveryPolicy {
    private var interval = MIN_INTERVAL_MS
    private var next = 0L
    fun reset(now: Long) { interval = MIN_INTERVAL_MS; next = now + interval }
    fun due(now: Long) = now >= next
    fun remaining(now: Long) = (next - now).coerceAtLeast(1L)
    fun attempted(now: Long) {
        interval = (interval * 2).coerceAtMost(MAX_INTERVAL_MS)
        next = now + interval
    }
    companion object {
        const val MIN_INTERVAL_MS = 5 * 60_000L
        const val MAX_INTERVAL_MS = 15 * 60_000L
    }
}
