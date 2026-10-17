package io.github.beacon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.beacon.app.data.remote.HttpMessageSync
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.TransportSettings
import io.github.beacon.app.domain.receive.*
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerProbeTest {
    private val untouchedStore =
        object : SyncStore {
            override fun save(message: IncomingMessage): SaveResult =
                error("Probe must not save messages")

            override fun ackCompleted(server: String, id: String) =
                error("Probe must not confirm messages")

            override fun pendingIds(server: String): List<String> =
                error("Probe must not read queues")

            override fun cursor(server: String): Long = error("Probe must not read cursors")

            override fun advanceCursor(server: String, sequence: Long) =
                error("Probe must not advance cursors")
        }

    private fun probe(status: Int, body: String, verify: (HttpMessageSync) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request =
                    executor.submit<List<String>> {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            val lines = mutableListOf<String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                lines.add(line)
                            }
                            val bytes = body.toByteArray()
                            socket.getOutputStream().apply {
                                write(
                                    "HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                write(bytes)
                                flush()
                            }
                            lines
                        }
                    }
                verify(
                    HttpMessageSync(
                        TransportSettings("http://127.0.0.1:${server.localPort}", "a".repeat(64)),
                        untouchedStore,
                    )
                )
                val lines = request.get(5, TimeUnit.SECONDS)
                assertEquals("GET /api/v1/capabilities HTTP/1.1", lines.first())
                assertTrue(
                    lines.any {
                        it.equals("Authorization: Bearer ${"a".repeat(64)}", ignoreCase = true)
                    }
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun authenticatedProbeDoesNotTouchMessages() =
        probe(
            200,
            """{"protocol":1,"server_id":"${"b".repeat(64)}","device_id":"${"c".repeat(64)}","transports":["http"]}""",
        ) {
            it.checkServer()
        }

    @Test
    fun rejectedCredentialIsNotReportedAsHealthy() =
        probe(401, "{}") {
            assertThrows(SyncAuthenticationException::class.java) { it.checkServer() }
        }

    @Test
    fun reachableIncompatibleServerIsNotReportedAsHealthy() =
        probe(200, """{"protocol":2}""") {
            assertThrows(SyncResponseException::class.java) { it.checkServer() }
        }
}
