package io.github.beacon.app.domain.model

/** Internal presentation state; never serialized as a protocol or IPC enum ordinal. */
enum class DataStatus { MISSING, WAITING, ACTIVE, STALE, EXPIRED, CLEARED }

data class TopicIdentity(val source: String, val application: String, val topic: String) {
    val localIdentity: String get() = "$application:$topic"
}

/** Remote-owned facts only. Local visibility, notification and widget preferences live elsewhere. */
data class TopicSnapshot(
    val identity: TopicIdentity,
    val waiting: Boolean = false,
    val directoryDeleted: Boolean = false,
    val cleared: Boolean = false,
    val staleAtMs: Long = 0,
    val expiresAtMs: Long = 0,
) {
    fun status(nowMs: Long): DataStatus = when {
        directoryDeleted -> DataStatus.MISSING
        cleared -> DataStatus.CLEARED
        waiting -> DataStatus.WAITING
        expiresAtMs > 0 && nowMs >= expiresAtMs -> DataStatus.EXPIRED
        staleAtMs > 0 && nowMs >= staleAtMs -> DataStatus.STALE
        else -> DataStatus.ACTIVE
    }
}
