package io.github.beacon.app.domain.model

/** Keep failure detection bounded; messages are pushed independently of this interval. */
object RealtimeHeartbeat {
    /**
     * 60 seconds is the documented and shipped default: ADR-044, the code62 heartbeat record and
     * the deployment guide all describe it, and it keeps silent-disconnect detection at roughly two
     * periods. A longer choice is a per-user option, never an undeclared new default.
     */
    const val DEFAULT_SECONDS = 60
    val options = listOf(30, 60, 90, 120, 180)

    fun validate(seconds: Int) {
        require(seconds in options)
    }

    /** A user's choice is the upper bound. Adapt only when a reconnect is already necessary. */
    fun forFailures(seconds: Int, failures: Int): Int {
        validate(seconds)
        require(failures >= 0)
        return if (failures >= 2) minOf(seconds, 60) else seconds
    }
}
