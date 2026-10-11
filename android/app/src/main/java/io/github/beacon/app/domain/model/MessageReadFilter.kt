package io.github.beacon.app.domain.model

enum class MessageReadFilter(val wireId: Int) {
    ALL(0),
    UNREAD(1),
    READ(2);

    companion object {
        fun fromWireId(id: Int): MessageReadFilter? = entries.firstOrNull { it.wireId == id }
    }
}
