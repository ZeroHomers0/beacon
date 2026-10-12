package io.github.beacon.app.domain.model

data class MessageQuery(
    val search: String = "",
    val serverId: String = "",
    val before: Long = Long.MAX_VALUE,
    val readFilter: MessageReadFilter = MessageReadFilter.ALL,
    val anchor: MessageKey? = null,
) {
    val groupFilter: String
        get() = search.takeIf { it.startsWith("group:") }?.removePrefix("group:") ?: ""

    val applicationFilter: String
        get() = search.takeIf { it.startsWith("app:") }?.removePrefix("app:") ?: ""

    val textSearch: String
        get() = if (groupFilter.isNotEmpty() || applicationFilter.isNotEmpty()) "" else search

    fun validate() {
        require(search.length <= 256 && serverId.length <= 128 && before > 0)
        anchor?.validate()
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}

data class MessagePage(
    val items: List<IncomingMessage>,
    val total: Long,
    val nextBefore: Long?,
    val unread: Long = 0,
    val undo: DeleteUndo? = null,
)
