package io.github.beacon.app.domain.model

enum class RecoveryStatus {
    NONE,
    DISABLED,
    BLOCKED,
    POWER_SAVING,
    START_REQUESTED,
    START_RESTRICTED,
    SETTINGS_ERROR,

    /** A locked-boot event was seen; credentials stay unavailable until the user unlocks. */
    AWAITING_UNLOCK,

    /** An unexpected exit is inside its backoff; another attempt is scheduled but not yet made. */
    CRASH_RETRY_WAITING,

    /** The bounded unexpected-exit budget ran out; receiving stays paused until the user acts. */
    CRASH_SUSPENDED,
}

data class RecoverySnapshot(
    val status: RecoveryStatus = RecoveryStatus.NONE,
    val atMs: Long = 0,
    /** Failed automatic restarts counted inside the current sliding window. */
    val attempts: Int = 0,
) {
    fun validate() {
        require(atMs >= 0)
        require(attempts >= 0)
    }
}
