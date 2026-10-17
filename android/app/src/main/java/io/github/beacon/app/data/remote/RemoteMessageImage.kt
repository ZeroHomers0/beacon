package io.github.beacon.app.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.beacon.app.data.local.ArchiveCrypto
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Request

/** Bounded HTTPS fetch; no redirects, private-network targets or unbounded bitmap allocations. */
object RemoteMessageImage {
    private const val MAX_CACHED_ENTRIES = 12
    private const val MAX_CACHED_BYTES = 6 * 1024 * 1024

    private val client =
        HttpClientFactory.publicOnly(HttpClientFactory.coldPool)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

    /**
     * One decoded bitmap per picture and requested size. A notification re-post, a quiet-hours
     * downgrade of the same batch and the message detail page all ask for the same URL, and this
     * connection pool deliberately keeps no idle socket, so an uncached call is always a cold TLS
     * handshake plus a decode. Bounded by both entry count and retained pixels.
     *
     * Failures are deliberately not remembered: the detail page offers an explicit retry, and a
     * negative entry would make that button permanently useless.
     */
    private val cache =
        object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
            private var bytes = 0L

            override fun put(key: String, value: Bitmap): Bitmap? {
                bytes += value.allocationByteCount.toLong()
                val previous = super.put(key, value)
                if (previous != null) bytes -= previous.allocationByteCount.toLong()
                return previous
            }

            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Bitmap>
            ): Boolean {
                val over = size > MAX_CACHED_ENTRIES || bytes > MAX_CACHED_BYTES.toLong()
                if (over) bytes -= eldest.value.allocationByteCount.toLong()
                return over
            }
        }

    fun load(url: String, maxDimension: Int = 1024): Bitmap? = runCatching {
        require(maxDimension in 1..1024)
        val uri = java.net.URI(url)
        require(url.length <= 2048 && uri.scheme == "https" && uri.userInfo == null)
        val key = "$maxDimension|$url"
        synchronized(cache) { cache[key] }
            ?.let {
                return it
            }
        val bitmap = fetch(url, maxDimension)
        if (bitmap != null) synchronized(cache) { cache[key] = bitmap }
        bitmap
    }
        .getOrNull()

    private fun fetch(url: String, maxDimension: Int): Bitmap? =
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
