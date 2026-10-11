package io.github.beacon.app.domain.model

enum class MessageAction(val wireId: Int) {
    MARK_READ(0),
    MARK_UNREAD(1),
    DELETE(2);

    companion object {
        fun fromWireId(id: Int): MessageAction? = entries.firstOrNull { it.wireId == id }
    }
}
