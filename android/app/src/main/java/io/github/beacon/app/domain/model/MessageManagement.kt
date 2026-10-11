package io.github.beacon.app.domain.model

data class MessageKey(val server: String, val id: String) : java.io.Serializable {
    fun validate() {
        require(
            server.isNotBlank() &&
                server.length <= MessageLimits.ID_CHARACTERS &&
                id.isNotBlank() &&
                id.length <= MessageLimits.ID_CHARACTERS
        )
    }
}

data class MessageMutation(val action: MessageAction, val keys: List<MessageKey>) {
    fun validate() {
        require(
            keys.size in 1..MessageLimits.MUTATION_BATCH_SIZE && keys.distinct().size == keys.size
        )
        keys.forEach(MessageKey::validate)
    }
}

// Retained solely for legacy Binder reply decoding; new runtime replies always use null.
data class DeleteUndo(val token: String, val expiresAtMs: Long, val count: Int)

data class MessageMutationResult(val changed: Int, val undo: DeleteUndo? = null)
