package io.github.beacon.app.domain.receive

class AdaptiveHeartbeatPolicy(
    rememberedSeconds: Int = MIN_SECONDS,
    failedSeconds: Int = 0,
    retryAfterMs: Long = 0,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val remembered = STEPS.last { it <= rememberedSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS) }

    var seconds = MIN_SECONDS
        private set

    @Volatile var failedSeconds = failedSeconds.takeIf { it in STEPS } ?: 0
        private set
    @Volatile var retryAfterMs = retryAfterMs.takeIf { it > nowMs() && it - nowMs() <= FAILURE_COOLDOWN_MS } ?: 0
        private set

    private var successes = 0
    private var warm = false
    private var verifiedSeconds = MIN_SECONDS

    fun acknowledged(): Int? {
        successes++
        if (successes < 3) return null
        val verified = seconds
        verifiedSeconds = verified
        successes = 0
        if (nowMs() >= retryAfterMs) {
            failedSeconds = 0
            retryAfterMs = 0
        }
        val candidate = if (!warm) remembered else STEPS.firstOrNull { it > seconds } ?: MAX_SECONDS
        warm = true
        if (failedSeconds == 0 || candidate < failedSeconds) seconds = candidate
        return verified
    }

    fun timedOut(): Int {
        failedSeconds = if (failedSeconds > 0 && nowMs() < retryAfterMs)
            minOf(failedSeconds, seconds) else seconds
        retryAfterMs = nowMs() + FAILURE_COOLDOWN_MS
        seconds = minOf(verifiedSeconds, STEPS.lastOrNull { it < seconds } ?: MIN_SECONDS)
        successes = 0
        warm = true
        return seconds
    }

    companion object {
        const val MIN_SECONDS = 60
        const val MAX_SECONDS = 300
        const val ACK_TIMEOUT_SECONDS = 15L
        const val ALIGN_WINDOW_MS = 10_000L
        const val FAILURE_COOLDOWN_MS = 30 * 60_000L
        private val STEPS = listOf(60, 90, 120, 180, 240, 300)
    }
}
