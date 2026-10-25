package io.github.beacon.app.support

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Loopback HTTP server shared by the binding, credential rotation and device revocation tests.
 *
 * `/api/v1/bind` answers with [bindStatus] and [bindBody]; `/api/v1/device/revoke` answers with the
 * mutable [revokeStatus] and [revokeResponse]; every other path answers with [DEVICE_SUMMARY].
 * Non-binding requests authenticate the bearer token against [acceptedToken] unless [enforceToken]
 * is off. A request that sends credentials together with `/bind` fails the test immediately.
 */
class LoopbackServer(
    private val bindStatus: Int,
    private val bindBody: String,
    private val enforceToken: Boolean = true,
) : AutoCloseable {
    private val socket = ServerSocket(0)
    private val executor = Executors.newSingleThreadExecutor()

    val endpoint = "http://127.0.0.1:${socket.localPort}"
    val responses=java.util.concurrent.ConcurrentHashMap<String,Pair<Int,String>>()
    val paths=java.util.concurrent.CopyOnWriteArrayList<String>()
    val posted = AtomicReference("")
    val probes = AtomicInteger()
    val acceptedToken = AtomicReference("d".repeat(64))
    val revokeStatus = AtomicInteger(200)
    val revokeResponse = AtomicReference("")
    val revokePosted = AtomicReference("")
    val revokeAuth = AtomicReference("")

    init {
        executor.execute {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client -> serve(client) }
                } catch (_: java.io.IOException) {
                    if (!socket.isClosed) throw IllegalStateException("Test server failed")
                }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 5000
        val input = client.getInputStream().bufferedReader()
        val start = input.readLine() ?: return
        val path=start.split(" ")[1].substringBefore("?")
        paths.add(path)
        val route=responses[path]
        var length = 0
        var authorized = ""
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Authorization:", true)) {
                authorized = line.substringAfter(':').trim()
                if (start.contains("/bind")) error("Binding must not send old credentials")
            }
            if (line.startsWith("Content-Length:", true))
                length = line.substringAfter(':').trim().toInt()
        }
        val chars = CharArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(chars, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        val body = String(chars)
        val binding = start.contains("/api/v1/bind")
        val revoking = start.contains("/api/v1/device/revoke")
        if (binding) posted.set(body)
        if (revoking) {
            revokePosted.set(body)
            revokeAuth.set(authorized)
        }
        if (!binding) probes.incrementAndGet()
        val result =
            when {
                binding -> bindBody
                revoking -> revokeResponse.get()
                route != null -> route.second
                else -> DEVICE_SUMMARY
            }
        val code =
            when {
                binding -> bindStatus
                revoking -> revokeStatus.get()
                enforceToken && authorized != "Bearer " + acceptedToken.get() -> 401
                route != null -> route.first
                else -> 200
            }
        val bytes = result.toByteArray()
        client.getOutputStream().apply {
            write(
                "HTTP/1.1 $code Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
            write(bytes)
            flush()
        }
    }

    override fun close() {
        socket.close()
        executor.shutdownNow()
    }

    companion object {
        val DEVICE_SUMMARY: String =
            """{"protocol":1,"server_id":"${"b".repeat(64)}","device_id":"${"c".repeat(64)}","transports":["http"]}"""
    }
}
