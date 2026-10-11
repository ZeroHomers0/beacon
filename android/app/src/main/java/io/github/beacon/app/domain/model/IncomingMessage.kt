package io.github.beacon.app.domain.model

data class IncomingMessage(
    val server: String,
    val id: String,
    val title: String,
    val body: String,
    val createdAtMs: Long = 0,
    val receivedAtMs: Long = 0,
    val isRead: Boolean = false,
    val subtitle: String = "",
    val priority: Int = 5,
    val reminderPending: Boolean = false,
    val bark: String = "",
)
