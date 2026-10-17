package io.github.beacon.app.domain.receive

/**
 * Learns only from completed probes; HTTP/server failures never change the idle interval.
 *
 * The ceiling is 300 seconds because the server closes a stream after 450 idle seconds
 * (`streamIdleTimeout`), the client waits up to 15 seconds for an acknowledgement, and the
 * deployment guide asks reverse proxies for a 450-second read timeout. Anything longer would
 * trade a real disconnect for a small saving.
 */
class AdaptiveHeartbeatPolicy(rememberedSeconds: Int = MIN_SECONDS) {
    private val remembered = rememberedSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    var seconds = MIN_SECONDS
        private set

    private var successes = 0

    /** Cleared once the remembered interval has been reached for this network. */
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
            // A network that already proved a longer interval skips straight to it.
            warm = true
            seconds = remembered
        } else if (recovery > 0) {
            recovery--
        } else {
            // Grow geometrically rather than one fixed step at a time. Three acknowledged probes
            // are already the evidence needed to try a longer interval. Stepping by a fixed 30
            // seconds instead took eight probes (~66 minutes) to walk 60 → 300 on a fresh install,
            // all of it spent at the short, expensive end; doubling does it in three (~21 minutes)
            // and every interval that is actually used has still been acknowledged first. The
            // ceiling bounds the overshoot, and [timedOut] falls back to the last verified value.
            seconds = (seconds * 2).coerceAtMost(MAX_SECONDS)
        }
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
