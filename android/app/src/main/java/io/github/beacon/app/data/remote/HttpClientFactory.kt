package io.github.beacon.app.data.remote

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * The single place every outbound HTTP client is built.
 *
 * There are two kinds of peer in this app and they need opposite DNS policies, so
 * the factory exposes exactly two entry points rather than one builder with a
 * "public network only" flag a caller could forget:
 *
 *  * [shared] — the user's own server. It is frequently on the LAN (192.168.x.x,
 *    fd00::/8), so private addresses are legitimate. This one client owns the
 *    connection pool and dispatcher; every other user-server client is derived
 *    from it with [OkHttpClient.newBuilder], which reuses both instead of paying
 *    for a private pool, dispatcher and idle thread per module.
 *  * [publicOnly] — addresses that came out of a message body or a release feed.
 *    Nothing routable to the local network, the loopback interface or a link-local
 *    address may be reached, and the rule is re-applied on every redirect hop
 *    because OkHttp resolves each new connection through the same [Dns].
 */
internal object HttpClientFactory {
    /**
     * One short-lived idle connection. Sync sessions issue ten-odd sequential
     * requests to a single host, so a kept-alive socket removes a TLS handshake
     * per page without holding anything open once the session ends.
     */
    val syncPool = ConnectionPool(1, 30, TimeUnit.SECONDS)

    /**
     * No idle socket at all. Clients that fetch one resource per user action would
     * otherwise hold an idle reader thread long after the result reached the screen.
     */
    val coldPool = ConnectionPool(0, 1, TimeUnit.SECONDS)

    /**
     * Base for every request to the configured server. Timeouts match one HTTP/1.1
     * round trip on a mobile link; callers that need a longer overall budget raise
     * `callTimeout` on the derived clone.
     */
    val shared: OkHttpClient =
        OkHttpClient.Builder()
            .connectionPool(syncPool)
            .connectTimeout(HttpSyncLimits.SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(HttpSyncLimits.SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(HttpSyncLimits.SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    /** Builder for a client whose targets came from untrusted input. */
    fun publicOnly(pool: ConnectionPool): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectionPool(pool)
            .dns(PublicInternetOnlyDns)
            .followRedirects(false)
            .followSslRedirects(false)

    /**
     * Rejects any name that resolves outside the public internet.
     *
     * A message body can carry an arbitrary URL and a release feed can be redirected;
     * neither is allowed to become a probe of the device's own network.
     */
    private object PublicInternetOnlyDns : Dns {
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
                                !(it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
                        }
                ) { "refusing to resolve a non-public address" }
            }
    }
}
