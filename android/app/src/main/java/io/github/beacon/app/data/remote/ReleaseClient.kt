package io.github.beacon.app.data.remote

import io.github.beacon.app.data.local.ArchiveCrypto
import io.github.beacon.app.domain.model.AppRelease
import io.github.beacon.app.domain.policy.ReleaseVersionPolicy
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads the published release and fetches its APK over HTTPS, with the same hardening as
 * [RemoteMessageImage]: no private-network targets, no cross-scheme redirects and a bounded body.
 *
 * Every failure is reported as a failure of the *check*, never as a wrong verdict: an unreachable
 * feed must not look like "已是最新版本".
 */
object ReleaseClient {
    const val FEED_URL = "https://api.github.com/repos/ZeroHomers0/beacon/releases/latest"

    /** A published APK is a few megabytes; anything past this is not the download we asked for. */
    const val MAX_APK_BYTES = 64L * 1024 * 1024

    private const val MAX_FEED_BYTES = 256 * 1024
    private const val MAX_CHECKSUMS_BYTES = 16 * 1024
    private const val MAX_URL = 2048
    private const val COPY_BUFFER = 64 * 1024

    private fun hardened(): OkHttpClient.Builder =
        HttpClientFactory.publicOnly(HttpClientFactory.coldPool)

    private val feed =
        hardened()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    // Release assets are served through a same-scheme redirect to a CDN, so this client follows
    // redirects. It is still unable to reach a private address: the DNS rule above is re-applied to
    // every hop, and a redirect that changes scheme is refused.
    private val assets =
        hardened()
            .followRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
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
            val release = ReleaseFeed.parse(body, checksums(body)) ?: return@runCatching null
            release.takeIf { ReleaseVersionPolicy.isNewer(it.version, currentVersion) }
        }

    /**
     * The release's `SHA256SUMS` asset, fetched best-effort. A missing sums file is not an error:
     * the release is still offered, and [download] then falls back to the archive check alone.
     */
    private fun checksums(body: String): String? {
        val url = ReleaseFeed.checksumsUrl(body) ?: return null
        return runCatching {
            val uri = URI(url)
            require(uri.isAbsolute && uri.scheme == "https" && uri.userInfo == null)
            fetch(feed, url, MAX_CHECKSUMS_BYTES).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /** Streams the release APK to [destination]; a partial or mismatched file is removed. */
    fun download(release: AppRelease, destination: File): Boolean {
        val downloaded =
            runCatching {
                require(release.downloadUrl.length <= MAX_URL)
                val uri = URI(release.downloadUrl)
                require(uri.isAbsolute && uri.scheme == "https" && uri.userInfo == null)
                val request = Request.Builder().url(release.downloadUrl).build()
                val digest = MessageDigest.getInstance("SHA-256")
                assets.newCall(request).execute().use { response ->
                    require(response.code == 200)
                    val body = requireNotNull(response.body)
                    // The size the feed advertises is advisory; the enforced bound is our own.
                    require(body.contentLength() <= MAX_APK_BYTES)
                    destination.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            copyBounded(input, output, MAX_APK_BYTES, digest)
                        }
                    }
                }
                // A published digest is authoritative: the archive check alone accepts any ZIP.
                // When the feed carried none the file is still required to be an APK.
                val actual = digest.digest().toHex()
                if (release.sha256.isNotEmpty()) require(actual == release.sha256)
                isZipArchive(destination)
            }
                .getOrDefault(false)
        if (!downloaded) destination.delete()
        return downloaded
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

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        digest: MessageDigest? = null,
    ) {
        val buffer = ByteArray(COPY_BUFFER)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            written += count
            require(written <= limit)
            digest?.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
    }

    private fun ByteArray.toHex(): String =
        buildString(size * 2) {
            for (byte in this@toHex) {
                append(HEX[(byte.toInt() shr 4) and 0xf])
                append(HEX[byte.toInt() and 0xf])
            }
        }

    private const val HEX = "0123456789abcdef"

    /** An APK is a ZIP archive; the install flow must never receive something else. */
    private fun isZipArchive(file: File): Boolean =
        file.length() >= 4 &&
            runCatching {
                file.inputStream().use { stream ->
                    val header = ByteArray(2)
                    stream.read(header) == 2 &&
                        header[0] == 'P'.code.toByte() &&
                        header[1] == 'K'.code.toByte()
                }
            }
                .getOrDefault(false)
}
