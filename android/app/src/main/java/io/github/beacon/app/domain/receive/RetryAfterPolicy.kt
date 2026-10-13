package io.github.beacon.app.domain.receive

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Retry-After may be delta seconds or an HTTP date. Invalid headers use normal backoff. */
object RetryAfterPolicy {
    const val MAX_DELAY_MS = 60 * 60_000L

    fun delayMs(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.all { it in '0'..'9' }) {
            val seconds = text.toLongOrNull() ?: return MAX_DELAY_MS
            return seconds.coerceAtMost(MAX_DELAY_MS / 1_000) * 1_000
        }
        return runCatching {
            val at =
                ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
            (at - nowMs).coerceIn(0, MAX_DELAY_MS)
        }
            .getOrNull()
    }

    fun from(error: Throwable): Long? =
        generateSequence(error) { it.cause }
            .take(10)
            .filterIsInstance<HttpTransientException>()
            .firstOrNull()
            ?.retryAfterMs
}
