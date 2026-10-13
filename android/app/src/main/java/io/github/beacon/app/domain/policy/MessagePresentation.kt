package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.IncomingMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object MessagePresentation {

    fun receivedDate(message: IncomingMessage, zone: ZoneId): LocalDate? =
        message.receivedAtMs
            .takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /** Explicit web links only. No custom schemes, credentials or automatic remote fetches. */
    fun links(body: String): List<String> = MessageMarkdown.links(body)
}
