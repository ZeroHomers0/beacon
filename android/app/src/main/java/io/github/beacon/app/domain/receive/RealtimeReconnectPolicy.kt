package io.github.beacon.app.domain.receive

import kotlin.math.min

/** Bounded reconnect delay; callers own the actual scheduler and cancellation. */
object RealtimeReconnectPolicy {
    private const val INITIAL_MS = 1_000L
    private const val MAX_MS = 5 * 60_000L

    fun delayMs(failureCount: Int): Long {
        require(failureCount >= 1)
        var delay = INITIAL_MS
        repeat((failureCount - 1).coerceAtMost(18)) { delay = min(MAX_MS, delay * 2) }
        return delay
    }
}
