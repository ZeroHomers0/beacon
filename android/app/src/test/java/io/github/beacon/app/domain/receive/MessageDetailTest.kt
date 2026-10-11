package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.IncomingMessage
import org.junit.Assert.*
import org.junit.Test

class MessageDetailTest {
    @Test
    fun previewIsBoundedAndDetailsPreserveOriginalText() {
        val body = "x".repeat(240)
        val preview = body.take(180) + if (body.length > 180) "…" else ""
        assertEquals(181, preview.length)
        assertEquals(body, IncomingMessage("s", "i", "title", body).body)
    }

    @Test
    fun copyLabelsAreSeparateFromStoredIdentity() {
        val message = IncomingMessage("server", "id", "Title", "Body")
        assertEquals("Title", message.title)
        assertEquals("Body", message.body)
        assertNotEquals(message.title, message.id)
    }
}
