package io.github.beacon.app.domain.model

data class MessageQuery(
    val search: String = "",
    val serverId: String = "",
    val before: Long = Long.MAX_VALUE,
    val readFilter: MessageReadFilter = MessageReadFilter.ALL,
) {
    fun validate() {
        require(search.length <= 256 && serverId.length <= 128 && before > 0)
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
