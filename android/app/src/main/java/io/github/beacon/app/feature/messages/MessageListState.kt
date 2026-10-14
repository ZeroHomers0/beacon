package io.github.beacon.app.feature.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageQuery
import io.github.beacon.app.domain.model.RuntimeState

/** Only inputs consumed by the list; connection diagnostics and detail state stay outside it. */
internal data class MessageListState(
    val messages: List<IncomingMessage>,
    val messagesLoaded: Boolean,
    val messageTotal: Long,
    val messageUnread: Long,
    val messageQuery: MessageQuery,
    val messageBusy: Boolean,
    val dataBusy: Boolean,
    val messageLoadFailed: Boolean,
    val nextMessageCursor: Long?,
    val runtimeConnected: Boolean,
    val configuredEndpoint: String,
)

@Composable
internal fun rememberMessageListState(state: RuntimeState): MessageListState =
    remember(
        state.messages, state.messagesLoaded, state.messageTotal, state.messageUnread,
        state.messageQuery, state.messageBusy, state.dataBusy, state.messageLoadFailed,
        state.nextMessageCursor, state.runtimeConnected, state.configuredEndpoint,
    ) {
        MessageListState(
            state.messages, state.messagesLoaded, state.messageTotal, state.messageUnread,
            state.messageQuery, state.messageBusy, state.dataBusy, state.messageLoadFailed,
            state.nextMessageCursor, state.runtimeConnected, state.configuredEndpoint,
        )
    }
