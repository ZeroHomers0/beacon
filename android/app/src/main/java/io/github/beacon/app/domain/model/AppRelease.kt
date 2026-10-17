package io.github.beacon.app.domain.model

/**
 * A published build of this application, as advertised by its release feed.
 *
 * The feed is external input: [notes] are displayed as plain text and [downloadUrl] is only ever
 * handed to the hardened downloader, which re-checks the scheme and the body size.
 */
data class AppRelease(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /**
     * Lowercase hex SHA-256 the feed published for the downloaded asset, or "" when the feed
     * carried none. The downloader refuses a body that does not match a declared digest.
     */
    val sha256: String = "",
)
