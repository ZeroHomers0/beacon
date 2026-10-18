package io.github.beacon.app.domain.model

/**
 * A published build of this application, as advertised by its release feed.
 *
 * The feed is external input: [notes] are displayed as plain text and [downloadUrl] is only ever
 * handed to the system browser, which needs an HTTPS link and nothing else.
 */
data class AppRelease(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)
