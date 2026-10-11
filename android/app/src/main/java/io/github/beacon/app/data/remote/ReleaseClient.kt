package io.github.beacon.app.data.remote

import io.github.beacon.app.data.local.ArchiveCrypto
import io.github.beacon.app.domain.model.AppRelease
import io.github.beacon.app.domain.policy.ReleaseVersionPolicy
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
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
    private const val MAX_URL = 2048
    private const val COPY_BUFFER = 64 * 1024

    private fun hardened(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            // One request per entry, so a cold connection is the normal case; the default
            // five-minute keep-alive would hold an idle socket open instead.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .dns(
                object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        InetAddress.getAllByName(hostname).toList().also { addresses ->
                            require(
                                addresses.isNotEmpty() &&
                                    addresses.all {
                                        !it.isAnyLocalAddress &&
                                            !it.isLoopbackAddress &&
                                            !it.isLinkLocalAddress &&
                                            !it.isSiteLocalAddress &&
                                            !it.isMulticastAddress &&
                                            !(it.address.size == 16 &&
                                                (it.address[0].toInt() and 0xfe) == 0xfc)
                                    }
                            )
                        }
                }
            )

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
            val release = ReleaseFeed.parse(body)
            release?.takeIf { ReleaseVersionPolicy.isNewer(it.version, currentVersion) }
        }

    /** Streams the release APK to [destination]; a partial or non-archive file is removed. */
    fun download(release: AppRelease, destination: File): Boolean {
        val downloaded =
            runCatching {
                require(release.downloadUrl.length <= MAX_URL)
                val uri = URI(release.downloadUrl)
                require(uri.isAbsolute && uri.scheme == "https" && uri.userInfo == null)
                val request = Request.Builder().url(release.downloadUrl).build()
                assets.newCall(request).execute().use { response ->
                    require(response.code == 200)
                    val body = requireNotNull(response.body)
                    // The size the feed advertises is advisory; the enforced bound is our own.
                    require(body.contentLength() <= MAX_APK_BYTES)
                    destination.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            copyBounded(input, output, MAX_APK_BYTES)
                        }
                    }
                }
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

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            written += count
            require(written <= limit)
            output.write(buffer, 0, count)
        }
    }

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
