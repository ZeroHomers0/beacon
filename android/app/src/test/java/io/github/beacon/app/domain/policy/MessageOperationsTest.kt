package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.IncomingMessage
import org.junit.Assert.*
import org.junit.Test

class MessageOperationsTest {
    @Test
    fun shareContainsOnlyVisibleContentAndPreservesLineBreaks() {
        val message =
            IncomingMessage(
                "private-server",
                "private-id",
                "title",
                "body\nnext",
                subtitle = "subtitle",
            )
        assertEquals("title\n\nsubtitle\n\nbody\nnext", MessageShare.text(message))
        assertFalse(MessageShare.text(message).contains("private-"))
    }

    @Test
    fun shareOmitsEmptySubtitleWithoutAddingEmptyBlocks() {
        assertEquals("title\n\nbody", MessageShare.text(IncomingMessage("s", "i", "title", "body")))
    }

    @Test
    fun jsonCurlUsesPublishKeyAndBarkJsonEndpoint() {
        val key = "a".repeat(64)
        val command = PushExamples.json("https://example.com/$key", false)
        assertTrue(command.contains("\"device_key\":\"$key\""))
        assertTrue(command.endsWith("'https://example.com/push'"))
        assertTrue(command.contains("\"subtitle\":\"JSON example\""))
        assertFalse(command.contains("receive_token"))
    }

    @Test
    fun jsonPowerShellUsesRealNewlineAndExplicitUtf8Bytes() {
        val command = PushExamples.json("https://example.com/" + "b".repeat(64), true)
        assertTrue(command.contains("\nInvoke-RestMethod -Method Post"))
        assertTrue(command.contains("UTF8.GetBytes(\$payload)"))
        assertFalse(command.contains("\\n"))
    }

    @Test
    fun encodedPushKeyIsDecodedBeforeJsonPayload() {
        val command = PushExamples.json("https://example.com/" + "%61".repeat(64), false)
        assertTrue(command.contains("\"device_key\":\"${"a".repeat(64)}\""))
        assertFalse(command.contains("%61"))
    }
}
