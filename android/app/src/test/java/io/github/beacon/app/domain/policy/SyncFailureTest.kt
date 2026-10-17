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
        assertEquals(SyncOutcome.TRANSIENT, SyncFailure.outcome(errors[3]))
    }

    @Test
    fun interruptedTlsRetriesButCertificateFailuresRemainBlocked() {
        val interrupted = javax.net.ssl.SSLException("connection reset")
        assertEquals(SyncOutcome.TRANSIENT, SyncFailure.outcome(interrupted))
        assertTrue(PollingPolicy.retry(SyncFailure.outcome(interrupted), 0))
        assertEquals("", PollingPolicy.blockingReason(SyncFailure.outcome(interrupted)))
        val invalidCertificate = javax.net.ssl.SSLHandshakeException("handshake failed").apply {
            initCause(java.security.cert.CertificateException("untrusted chain"))
        }
        assertEquals(SyncOutcome.TLS, SyncFailure.outcome(invalidCertificate))
        assertFalse(PollingPolicy.retry(SyncFailure.outcome(invalidCertificate), 0))
        assertEquals(
            SyncOutcome.TLS,
            SyncFailure.outcome(javax.net.ssl.SSLPeerUnverifiedException("hostname mismatch")),
        )
    }

    @Test
    fun typedStageAndHttpCodeIdentifyProxyAndAckProblems() {
        assertTrue(
            SyncFailure.describe(
                    HttpRejectedException(404, SyncStage.CAPABILITIES),
                    SyncOutcome.PROTOCOL,
                )
                .contains("未找到服务")
        )
        assertTrue(
            SyncFailure.describe(HttpTransientException(502, SyncStage.ACK), SyncOutcome.TRANSIENT)
                .contains("服务器暂时不可用")
        )
        assertTrue(
            SyncFailure.describe(HttpRejectedException(302), SyncOutcome.PROTOCOL)
                .contains("服务器地址已跳转")
        )
    }
}
