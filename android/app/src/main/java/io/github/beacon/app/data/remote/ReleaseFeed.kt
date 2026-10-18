package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.AppRelease
import org.json.JSONObject

/**
 * Decodes a release feed entry. The payload is untrusted, so every field is optional and a release
 * that carries no installable asset is ignored instead of being offered as an unusable update.
 */
object ReleaseFeed {
    /** Asset names the release job publishes, signed build first. */
    private val ASSET_NAMES = listOf("beacon-release.apk", "beacon-release-unsigned.apk")
    private const val MAX_VERSION = 64
    private const val MAX_URL = 2048
    /** Release notes are shown inside a dialog, not archived; keep the retained text bounded. */
    private const val MAX_NOTES = 4000

    /** The release described by [body], or null when it carries nothing installable. */
    fun parse(body: String): AppRelease? {
        val release = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // Drafts and pre-releases are not offered to users of a stable build.
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val version = release.optString("tag_name").trim().removePrefix("v")
        if (version.isEmpty() || version.length > MAX_VERSION) return null
        // The version is shown next to the download link, so it stays a plain tag token.
        if (!version.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        val asset = assetNamed(release, ASSET_NAMES) ?: return null
        val url = asset.optString("browser_download_url").trim()
        if (url.isEmpty() || url.length > MAX_URL) return null
        return AppRelease(
            version = version,
            notes = release.optString("body").trim().take(MAX_NOTES),
            downloadUrl = url,
            sizeBytes = asset.optLong("size").coerceAtLeast(0L),
        )
    }

    private fun assetNamed(release: JSONObject, names: List<String>): JSONObject? {
        for (name in names) {
            assetMatching(release) { it == name }?.let { return it }
        }
        return null
    }

    /** The first matching asset, in the order [names] declares, from a predicate form. */
    private fun assetMatching(release: JSONObject, matches: (String) -> Boolean): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (matches(asset.optString("name"))) return asset
        }
        return null
    }
}
