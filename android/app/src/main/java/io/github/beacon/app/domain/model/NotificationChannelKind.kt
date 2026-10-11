package io.github.beacon.app.domain.model

/** Stable system channel identifiers; changing them would discard user channel preferences. */
enum class NotificationChannelKind(val id: String) {
    FRESH("beacon_messages_v1"),
    HISTORY("beacon_history_v1"),
    RUNTIME("beacon.realtime.v1"),
    IMPORTANT("beacon_important_v1"),
}
