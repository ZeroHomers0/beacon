package io.github.beacon.app.domain.policy

/**
 * Decides whether the saved server still needs a network probe.
 *
 * A live WebSocket already proves what the probe would: the host resolved, TLS verified, the
 * credentials were accepted and the stream protocol matched. Heartbeats keep proving it, so a
 * connected stream with a recent beat is stronger evidence than a single request. The probe is
 * therefore only needed when the stream is absent (power saving, paused, dropped) or when the
 * cached verdict has aged past [ProbeFreshnessMs].
 */
object ServerStatusPolicy {
    /** How long a probe verdict stays believable without a live stream. */
    const val ProbeFreshnessMs = 60_000L

    /** A beat older than this many intervals means the socket is probably half-open. */
    const val HeartbeatGraceFactor = 2L

    /** Fallback when the plan does not state an interval. */
    const val DefaultHeartbeatIntervalMs = 30_000L

    /** True when the stream is connected and a heartbeat arrived inside the grace window. */
    fun websocketLive(
        running: Boolean,
        heartbeatAgeMs: Long,
        heartbeatIntervalMs: Long,
    ): Boolean {
        if (!running) return false
        if (heartbeatAgeMs < 0L) return false
        return heartbeatAgeMs <= grace(heartbeatIntervalMs)
    }

    /** True when the toolbar needs a fresh probe because nothing else can vouch for the server. */
    fun needsProbe(websocketLive: Boolean, verdictAgeMs: Long): Boolean {
        if (websocketLive) return false
        if (verdictAgeMs < 0L) return true
        return verdictAgeMs >= ProbeFreshnessMs
    }

    /** How long a verdict stays fresh while no stream can vouch for it. */
    fun grace(heartbeatIntervalMs: Long): Long =
        (if (heartbeatIntervalMs > 0L) heartbeatIntervalMs else DefaultHeartbeatIntervalMs) *
            HeartbeatGraceFactor
}
