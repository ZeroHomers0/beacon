package io.github.beacon.app.domain.model

enum class OutdatedReminder {
    SILENT,
    SAVE_ONLY,
}

data class NotificationSettings(
    val freshMinutes: Int = 5,
    val outdatedReminder: OutdatedReminder = OutdatedReminder.SILENT,
) {
    fun validate() {
        require(freshMinutes in 1..1440)
    }

    val freshWindowMs: Long
        get() = freshMinutes * 60_000L
}
