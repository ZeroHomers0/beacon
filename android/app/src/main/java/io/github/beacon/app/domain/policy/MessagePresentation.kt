package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.IncomingMessage
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object MessagePresentation {
    private const val MAX_LINKS = 10
    private const val MAX_URL_CHARACTERS = 2048

    fun receivedDate(message: IncomingMessage, zone: ZoneId): LocalDate? =
        message.receivedAtMs
            .takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /** Explicit web links only. No custom schemes, credentials or automatic remote fetches. */
    fun links(body: String): List<String> =
        Regex("""https?://[^\s<>"\u0000-\u001f]+""", RegexOption.IGNORE_CASE)
            .findAll(body.take(io.github.beacon.app.domain.model.MessageLimits.BODY_BYTES))
            .map { it.value.trimEnd('.', ',', '，', '。', '；', ';', '!', '！') }
            .filter { raw ->
                raw.length <= MAX_URL_CHARACTERS &&
                    runCatching {
                            val uri = URI(raw)
                            uri.host != null && uri.rawUserInfo == null && uri.port in -1..65535
                        }
                        .getOrDefault(false)
            }
            .distinct()
            .take(MAX_LINKS)
            .toList()
}
