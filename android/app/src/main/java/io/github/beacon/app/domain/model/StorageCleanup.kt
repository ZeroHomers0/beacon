package io.github.beacon.app.domain.model

data class CleanupPolicy(
    val days: Int = 30,
    val maxMessages: Int = 10000,
    val keepUnread: Boolean = true,
) {
    fun validate() {
        require(days in 0..MAX_RETENTION_DAYS)
        require(maxMessages in MIN_MESSAGES..MAX_MESSAGES)
    }

    companion object {
        const val MAX_RETENTION_DAYS = 3650
        const val MIN_MESSAGES = 1
        const val MAX_MESSAGES = 100000
        const val BATCH_SIZE = 100
        const val PREVIEW_LIFETIME_MS = 5 * 60 * 1000L
    }
}

data class CleanupPreview(
    val token: String,
    val total: Long,
    val unread: Long,
    val pending: Long,
    val databaseBytes: Long,
    val eligible: Long,
    val protected: Long,
    val planned: Int,
    val reusableBytes: Long = 0,
    val pendingNotifications: Long = 0,
    val tombstones: Long = 0,
    val walBytes: Long = 0,
    val availableBytes: Long = -1,
    val sampledAtMs: Long = 0,
)

/** Runtime-owned configuration; automatic removal is explicitly opt-in. */
data class CleanupSettings(
    val policy: CleanupPolicy = CleanupPolicy(),
    val automatic: Boolean = false,
    val lastResult: String = "尚未自动清理",
)
