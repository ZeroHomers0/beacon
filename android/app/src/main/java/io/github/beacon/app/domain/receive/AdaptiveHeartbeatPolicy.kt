package io.github.beacon.app.domain.receive

/** Learns only from completed probes; HTTP/server failures never change the idle interval. */
class AdaptiveHeartbeatPolicy(rememberedSeconds: Int = MIN_SECONDS) {
    private val remembered = rememberedSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
    var seconds = MIN_SECONDS
        private set

    private var successes = 0
    private var warm = false
    private var recovery = 0
    private var verifiedSeconds = MIN_SECONDS

    /** Return a verified interval to persist, never the next untested probe interval. */
    fun acknowledged(): Int? {
        successes++
        if (successes < 3) return null
        val verified = seconds
        verifiedSeconds = verified
        successes = 0
        if (!warm) {
            warm = true
            seconds = remembered
        } else if (recovery > 0) {
            recovery--
        } else seconds = (seconds + STEP_SECONDS).coerceAtMost(MAX_SECONDS)
        return verified
    }

    fun timedOut(): Int {
        seconds = minOf(verifiedSeconds, (seconds - STEP_SECONDS).coerceAtLeast(MIN_SECONDS))
        successes = 0
        warm = true
        recovery = 3
        return seconds
    }

    companion object {
        const val MIN_SECONDS = 60
        const val MAX_SECONDS = 300
        const val STEP_SECONDS = 30
        const val ACK_TIMEOUT_SECONDS = 15L
        const val ALIGN_WINDOW_MS = 10_000L
    }
}
