package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.receive.HttpRejectedException
import io.github.beacon.app.domain.receive.HttpTransientException
import io.github.beacon.app.domain.receive.PollingPolicy
import io.github.beacon.app.domain.receive.SyncAuthenticationException
import io.github.beacon.app.domain.receive.SyncStage
import org.junit.Assert.*
import org.junit.Test

class SyncFailureTest {
    @Test
    fun transientHttpErrorsRetryWithoutBlockingWhileRedirectsAreProtocolErrors() {
        for (status in listOf(429, 500, 502, 503)) {
            val result = SyncFailure.outcome(HttpTransientException(status, SyncStage.ACK))
            assertEquals(SyncOutcome.TRANSIENT, result)
            assertTrue(PollingPolicy.retry(result, 0))
            assertEquals("", PollingPolicy.blockingReason(result))
        }
        assertEquals(SyncOutcome.PROTOCOL, SyncFailure.outcome(HttpRejectedException(302)))
        assertEquals(SyncOutcome.AUTH, SyncFailure.outcome(SyncAuthenticationException(403)))
        assertEquals(
            SyncOutcome.STORAGE,
            SyncFailure.outcome(IllegalStateException(), storage = true),
        )
    }

    @Test
    fun exceptionMessagesAndRemotePayloadsAreNeverDisplayed() {
        val secret = "https://secret.example/token=" + "a".repeat(64)
        val errors =
            listOf(
                java.net.UnknownHostException(secret),
                java.net.SocketTimeoutException(secret),
                java.net.ConnectException(secret),
                javax.net.ssl.SSLHandshakeException(secret),
                IllegalStateException(secret),
            )
        errors.forEach { error ->
            val text = SyncFailure.describe(error, SyncFailure.outcome(error))
            assertFalse(text.contains(secret))
            assertFalse(text.contains("secret.example"))
            assertFalse(text.contains("a".repeat(64)))
        }
        assertEquals(SyncOutcome.TLS, SyncFailure.outcome(errors[3]))
    }

    @Test
    fun typedStageAndHttpCodeIdentifyProxyAndAckProblems() {
        assertTrue(
            SyncFailure.describe(
                    HttpRejectedException(404, SyncStage.CAPABILITIES),
                    SyncOutcome.PROTOCOL,
                )
                .contains("能力握手 未完成")
        )
        assertTrue(
            SyncFailure.describe(HttpTransientException(502, SyncStage.ACK), SyncOutcome.TRANSIENT)
                .contains("向服务器确认接收 未完成")
        )
        assertTrue(
            SyncFailure.describe(HttpRejectedException(302), SyncOutcome.PROTOCOL)
                .contains("最终 HTTPS 根地址")
        )
    }
}
