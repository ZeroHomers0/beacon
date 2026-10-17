package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.RuntimeState

object MessageWindowPolicy {
    fun afterDeletion(
        state: RuntimeState,
        keys: Set<MessageKey>,
        changed: Int,
        total: Long?,
        unread: Long?,
    ): RuntimeState {
        val removedUnread = state.messages.count {
            MessageKey(it.server, it.id) in keys && !it.isRead
        }
        return state.copy(
            messages = state.messages.filterNot { MessageKey(it.server, it.id) in keys },
            messageTotal = total ?: (state.messageTotal - changed).coerceAtLeast(0),
            messageUnread = unread ?: (state.messageUnread - removedUnread).coerceAtLeast(0),
        )
    }
}
