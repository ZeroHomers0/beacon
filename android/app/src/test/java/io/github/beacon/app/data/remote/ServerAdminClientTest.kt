package io.github.beacon.app.data.remote

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ServerAdminClientTest {
    private val token = "a".repeat(64)
    private val http =
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    @Test
    fun rejectsUnsafeEndpointsAndMalformedCredentials() {
        for (endpoint in
            listOf(
                "http://example.com",
                "https://user:secret@example.com",
                "https://example.com/path",
                "https://example.com?token=x",
                "https://example.com#fragment",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                ServerAdminClient(endpoint, token)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerAdminClient("https://example.com", "bad")
        }
    }

    @Test
    fun usesSeparateBearerCredentialAndStructuredPostBodies() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"binding_code\":\"one-use\"}"))
            val client =
                ServerAdminClient(server.url("/").toString(), token, http, allowLoopback = true)
            assertEquals("one-use", client.binding("phone \"name\"").getString("binding_code"))
            val request = server.takeRequest()
            assertEquals("/api/admin/binding", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            assertEquals(
                "phone \"name\"",
                org.json.JSONObject(request.body.readUtf8()).getString("name"),
            )
            assertNull(request.getHeader("Cookie"))
        }
    }

    @Test
    fun neverForwardsAdministratorTokenThroughRedirects() {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.enqueue(
                    MockResponse().setResponseCode(302).setHeader("Location", second.url("/stolen"))
                )
                val client =
                    ServerAdminClient(first.url("/").toString(), token, http, allowLoopback = true)
                val failure = assertThrows(AdminRequestException::class.java) { client.overview() }
                assertEquals(302, failure.status)
                assertEquals(0, second.requestCount)
            }
        }
    }

    @Test
    fun authenticationFailureIsReportedWithoutExposingResponseSecrets() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret server content"))
            val client =
                ServerAdminClient(server.url("/").toString(), token, http, allowLoopback = true)
            val failure = assertThrows(AdminRequestException::class.java) { client.overview() }
            assertEquals(401, failure.status)
            assertFalse(failure.message!!.contains("secret"))
            assertFalse(failure.message!!.contains(token))
        }
    }
}
