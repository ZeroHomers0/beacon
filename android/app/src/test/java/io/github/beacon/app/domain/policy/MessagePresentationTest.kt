package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.IncomingMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MessagePresentationTest {
    @Test
    fun dateGroupsUseReceivedTimeAndCurrentTimezone() {
        val time = Instant.parse("2026-10-09T18:00:00Z").toEpochMilli()
        val message =
            IncomingMessage("server", "id", "title", "body", createdAtMs = 1, receivedAtMs = time)
        assertEquals(
            LocalDate.parse("2026-10-10"),
            MessagePresentation.receivedDate(message, ZoneId.of("Asia/Shanghai")),
        )
        assertEquals(
            LocalDate.parse("2026-10-09"),
            MessagePresentation.receivedDate(message, ZoneId.of("UTC")),
        )
        assertNull(
            MessagePresentation.receivedDate(message.copy(receivedAtMs = 0), ZoneId.of("UTC"))
        )
    }

    @Test
    fun onlyValidWebLinksAreExtractedAndDeduplicated() {
        assertEquals(
            listOf("https://example.com/page?x=1#section"),
            MessagePresentation.links(
                "https://example.com/page?x=1#section https://example.com/page?x=1#section。 javascript:alert(1) file:///tmp/test"
            ),
        )
        assertTrue(
            MessagePresentation.links(
                    "https://user:password@example.com/ https://example.com:99999/ https://"
                )
                .isEmpty()
        )
    }

    @Test
    fun linkCountAndLengthAreBounded() {
        assertEquals(
            10,
            MessagePresentation.links((1..100).joinToString(" ") { "https://example.com/$it" })
                .size,
        )
        assertTrue(MessagePresentation.links("https://example.com/" + "a".repeat(3000)).isEmpty())
    }

    @Test
    fun copyCommandsPreservePushKeyAndEncodeBodySpaces() {
        val address = "https://example.com/" + "a".repeat(64)
        assertEquals(
            "curl --fail-with-body '$address/Beacon/Hello%20from%20Beacon'",
            PushExamples.create(address, false),
        )
        assertEquals(
            "Invoke-RestMethod -Method Get -Uri '$address/Beacon/Hello%20from%20Beacon'",
            PushExamples.create(address, true),
        )
    }

    @Test
    fun shellQuotesInDeploymentPrefixAreEscaped() {
        val address = "https://example.com/it's/" + "a".repeat(64)
        assertTrue(PushExamples.create(address, true).contains("it''s"))
        assertTrue(PushExamples.create(address, false).contains("it'\"'\"'s"))
    }

    @Test
    fun invalidCredentialAddressesAreRejected() {
        for (address in
            listOf(
                "https://example.com/not-a-key",
                "https://user:pass@example.com/" + "a".repeat(64),
                "https://example.com/" + "a".repeat(64) + "?token=secret",
                "file:///" + "a".repeat(64),
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                PushExamples.create(address, false)
            }
        }
    }
}
