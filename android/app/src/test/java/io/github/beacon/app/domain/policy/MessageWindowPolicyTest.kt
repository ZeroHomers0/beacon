package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageQuery
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.domain.model.RuntimeState
import org.junit.Assert.*
import org.junit.Test

class MessageWindowPolicyTest {
    @Test
    fun deletionRetainsLoadedPagesCursorAndFilter() {
        val messages = (1..60).map {
            IncomingMessage("server", "$it", "title", "body", 1L)
        }
        val state = RuntimeState(
            messages = messages,
            messageTotal = 100,
            messageUnread = 100,
            messagePageIndex = 2,
            nextMessageCursor = 40L,
            messageQuery = MessageQuery("test", readFilter = MessageReadFilter.UNREAD),
        )
        val updated = MessageWindowPolicy.afterDeletion(
            state, setOf(MessageKey("server", "45")), 1, 99L, 99L,
        )
        assertEquals(messages.filterNot { it.id == "45" }, updated.messages)
        assertEquals(2, updated.messagePageIndex)
        assertEquals(state.nextMessageCursor, updated.nextMessageCursor)
        assertEquals(state.messageQuery, updated.messageQuery)
        assertEquals(99L, updated.messageTotal)
        assertEquals(99L, updated.messageUnread)
    }

    @Test
    fun unavailableCountsDoNotPreventRemoval() {
        val state = RuntimeState(
            messages = listOf(IncomingMessage("server", "id", "title", "body", 1L)),
            messageTotal = 1,
            messageUnread = 1,
        )
        val updated = MessageWindowPolicy.afterDeletion(
            state, setOf(MessageKey("server", "id")), 1, null, null,
        )
        assertTrue(updated.messages.isEmpty())
        assertEquals(0L, updated.messageTotal)
        assertEquals(0L, updated.messageUnread)
    }
}
