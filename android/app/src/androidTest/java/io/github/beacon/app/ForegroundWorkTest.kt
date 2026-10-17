package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.MessageListState
import io.github.beacon.app.feature.messages.rememberMessageListState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ForegroundWorkTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Composable
    private fun ListConsumer(state: MessageListState, committed: (MessageListState) -> Unit) {
        SideEffect { committed(state) }
    }

    @Test fun diagnosticsSkipListConsumerButMessageChangesUpdateIt() {
        val runtime = mutableStateOf(RuntimeState(messagesLoaded = true))
        var commits = 0
        var last: MessageListState? = null
        val committed: (MessageListState) -> Unit = { commits++; last = it }
        rule.runOnUiThread {
            rule.activity.setContent { ListConsumer(rememberMessageListState(runtime.value), committed) }
        }
        rule.waitForIdle()
        val initial = last
        val initialCommits = commits
        rule.runOnIdle {
            runtime.value = runtime.value.copy(
                realtimeDiagnostics = RealtimeDiagnostics(attempts = 5, retries = 2),
                realtimeRunning = true,
                realtimeStatus = "synthetic status",
                serverChecking = true,
                selectedMessage = IncomingMessage("test", "detail", "detail", "body"),
            )
        }
        rule.waitForIdle()
        assertSame(initial, last)
        assertEquals(initialCommits, commits)

        rule.runOnIdle {
            runtime.value = runtime.value.copy(
                messages = listOf(IncomingMessage("test", "new", "new", "body")),
                messageTotal = 1,
                messageUnread = 1,
            )
        }
        rule.waitForIdle()
        assertEquals(initialCommits + 1, commits)
        assertEquals("new", last!!.messages.single().id)
        assertEquals(1L, last!!.messageUnread)

        rule.runOnIdle {
            runtime.value = runtime.value.copy(
                messageQuery = MessageQuery(search = "updated"),
                messageLoadFailed = true,
                nextMessageCursor = 25,
                transportBusy = true,
                configuredEndpoint = "https://example.invalid",
                runtimeConnected = true,
            )
        }
        rule.waitForIdle()
        assertEquals(initialCommits + 2, commits)
        assertTrue(last!!.messageBusy)
        assertTrue(last!!.messageLoadFailed)
        assertTrue(last!!.runtimeConnected)
        assertEquals("updated", last!!.messageQuery.search)
        assertEquals(25L, last!!.nextMessageCursor)
    }
}
