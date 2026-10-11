package io.github.beacon.app.domain.model

import org.junit.Assert.assertThrows
import org.junit.Test

class TransportSettingsTest {
    private val token = "a".repeat(64)

    @Test
    fun acceptsHttpsAndDebugLoopback() {
        TransportSettings("https://example.org:8443", token).validate()
        TransportSettings("http://127.0.0.1:8080", token).validate(allowLoopback = true)
    }

    @Test
    fun rejectsCredentialsAndUntrustedCleartextOrigins() {
        for (endpoint in
            listOf(
                "http://192.168.1.2",
                "https://user:password@example.org",
                "https://example.org/path",
                "https://example.org?key=x",
                "https://example.org#x",
                "file:///secret",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                TransportSettings(endpoint, token).validate()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransportSettings("https://example.org", "bad").validate()
        }
    }

    @Test
    fun blankCredentialReusesSavedSecretAndNewCredentialOverridesIt() {
        val saved = "b".repeat(64)
        org.junit.Assert.assertEquals(
            saved,
            TransportSettings("https://example.org", "").resolved { saved }.token,
        )
        org.junit.Assert.assertEquals(
            token,
            TransportSettings("https://example.org", token)
                .resolved { error("Must not read old secret") }
                .token,
        )
        assertThrows(IllegalArgumentException::class.java) {
            TransportSettings("https://example.org", "").resolved { "" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransportSettings("http://untrusted.example", "").resolved { saved }
        }
    }
}
