package io.github.beacon.app.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.beacon.app.data.local.ArchiveCrypto
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

/** Bounded HTTPS fetch; no redirects, private-network targets or unbounded bitmap allocations. */
object RemoteMessageImage {
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            // Images arrive one at a time from a scrollable list, so every fetch is a cold
            // connection anyway. The default five-minute keep-alive would hold an idle socket
            // (and its reader) open long after the bitmap reached the screen.
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
            .build()

    fun load(url: String, maxDimension: Int = 1024): Bitmap? = runCatching {
        require(maxDimension in 1..1024)
        val uri = java.net.URI(url)
        require(url.length <= 2048 && uri.scheme == "https" && uri.userInfo == null)
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            require(response.code == 200)
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 2 * 1024 * 1024)
            val bytes = body.byteStream().use { ArchiveCrypto.readBounded(it, 2 * 1024 * 1024) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(
                bounds.outWidth > 0 &&
                    bounds.outHeight > 0 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000
            )
            val options = BitmapFactory.Options()
            while (
                bounds.outWidth / options.inSampleSize > maxDimension ||
                    bounds.outHeight / options.inSampleSize > maxDimension
            ) options.inSampleSize *= 2
            requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        }
    }
        .getOrNull()
}
