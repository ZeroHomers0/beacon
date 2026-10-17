package io.github.beacon.app.domain.model

import org.junit.Assert.assertThrows
import org.junit.Test

class BindingRequestTest {
    @Test
    fun acceptsHttpsAndDebugLoopback() {
        BindingRequest("https://example.com", "a".repeat(32), "小米 17").validate()
        BindingRequest("http://127.0.0.1:8080", "a".repeat(32), "").validate(true)
    }

    @Test
    fun forbidsCredentialLeakThroughUrl() {
        listOf(
                "https://user:password@example.com",
                "https://example.com/path",
                "https://example.com?secret=x",
                "https://example.com#x",
                "http://example.com",
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) {
                    BindingRequest(it, "a".repeat(32), "phone").validate()
                }
            }
    }

    @Test
    fun rejectsMalformedOrOversizedInput() {
        listOf("", "a".repeat(31), "g".repeat(32), "a".repeat(33)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                BindingRequest("https://example.com", it, "phone").validate()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BindingRequest("https://example.com", "a".repeat(32), "米".repeat(43)).validate()
        }
    }
}
