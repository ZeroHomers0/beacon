package io.github.beacon.app.domain.model

/** Allowlisted causes only: never include exception messages, hosts or credentials. */
enum class RealtimeFailure {
    NONE,
    AUTH,
    CERTIFICATE_EXPIRED,
    CERTIFICATE_NOT_YET_VALID,
    CERTIFICATE_UNTRUSTED,
    HOSTNAME,
    TLS_INTERRUPTED,
    DNS,
    TIMEOUT,
    CONNECTION,
    CONFIG,
    NETWORK,
    INTERNAL,
}

data class RealtimeDiagnostics(
    val failure: RealtimeFailure = RealtimeFailure.NONE,
    val attempts: Int = 0,
    val retries: Int = 0,
    val connectedAtMs: Long = 0,
) {
    fun validate() {
        require(attempts >= 0 && retries in 0..100 && connectedAtMs >= 0)
    }
}
