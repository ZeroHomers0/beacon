package io.github.beacon.app.data.remote

import io.github.beacon.app.data.local.ArchiveCrypto
import io.github.beacon.app.domain.model.AppRelease
import io.github.beacon.app.domain.policy.ReleaseVersionPolicy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads the published release over HTTPS, with the same hardening as [RemoteMessageImage]: no
 * private-network targets, no cross-scheme redirects and a bounded body.
 *
 * Every failure is reported as a failure of the *check*, never as a wrong verdict: an unreachable
 * feed must not look like "已是最新版本".
 *
 * The client stops at the feed. The app never fetches the archive itself: the update dialog hands
 * [AppRelease.downloadUrl] to the system browser, which owns the download and the install.
 */
object ReleaseClient {
    const val FEED_URL = "https://api.github.com/repos/ZeroHomers0/beacon/releases/latest"

    private const val MAX_FEED_BYTES = 256 * 1024
    private const val MAX_URL = 2048

    private fun hardened(): OkHttpClient.Builder =
        HttpClientFactory.publicOnly(HttpClientFactory.coldPool)

    private val feed =
        hardened()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    /**
     * The newest published release, but only when it is newer than [currentVersion].
     *
     * A successful result of null means the feed was read and the installed build is current; a
     * failure means no verdict could be reached.
     */
    fun latest(currentVersion: String): Result<AppRelease?> =
        runCatching {
            val body = fetch(feed, FEED_URL, MAX_FEED_BYTES).toString(Charsets.UTF_8)
            val release = ReleaseFeed.parse(body) ?: return@runCatching null
            release.takeIf { ReleaseVersionPolicy.isNewer(it.version, currentVersion) }
        }

    private fun fetch(client: OkHttpClient, url: String, limit: Int): ByteArray {
        val uri = URI(url)
        require(url.length <= MAX_URL && uri.scheme == "https" && uri.userInfo == null)
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            require(response.code == 200)
            val body = requireNotNull(response.body)
            require(body.contentLength() <= limit.toLong())
            body.byteStream().use { ArchiveCrypto.readBounded(it, limit) }
        }
    }
}
