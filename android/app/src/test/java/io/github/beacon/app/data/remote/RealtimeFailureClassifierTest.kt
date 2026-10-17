package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.RealtimeFailure
import io.github.beacon.app.domain.receive.NotificationIntent
import io.github.beacon.app.domain.receive.NotificationPolicy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.*
import org.junit.Test

class RealtimeFailureClassifierTest {
    @Test
    fun wrappedCertificateFailuresRemainTerminalAndSpecific() {
        assertEquals(
            RealtimeFailure.CERTIFICATE_EXPIRED,
            RealtimeFailureClassifier.classify(
                SSLHandshakeException("private address").apply {
                    initCause(CertificateExpiredException())
                }
            ),
        )
        assertEquals(
            RealtimeFailure.CERTIFICATE_UNTRUSTED,
            RealtimeFailureClassifier.classify(
                RuntimeException(CertificateException("private certificate"))
            ),
        )
        assertEquals(
            RealtimeFailure.HOSTNAME,
            RealtimeFailureClassifier.classify(SSLPeerUnverifiedException("private hostname")),
        )
    }

    @Test
    fun transportFailuresDoNotDependOnSensitiveExceptionText() {
        assertEquals(
            RealtimeFailure.DNS,
            RealtimeFailureClassifier.classify(
                RuntimeException(UnknownHostException("secret host"))
            ),
        )
        assertEquals(
            RealtimeFailure.TIMEOUT,
            RealtimeFailureClassifier.classify(SocketTimeoutException("secret token")),
        )
        assertEquals(
            RealtimeFailure.TLS_INTERRUPTED,
            RealtimeFailureClassifier.classify(SSLHandshakeException("temporary EOF")),
        )
    }

    @Test
    fun cyclicCauseChainsAreBounded() {
        val first = RuntimeException()
        val second = RuntimeException()
        first.initCause(second)
        second.initCause(first)
        assertEquals(RealtimeFailure.INTERNAL, RealtimeFailureClassifier.classify(first))
    }

    @Test
    fun onlySingleMessageNotificationsHaveDetailDestination() {
        val key = MessageKey("server", "message")
        val first = NotificationIntent(1, 1000, "title", "body", key)
        assertEquals(key, NotificationPolicy.batch(listOf(first), 1000).key)
        assertNull(NotificationPolicy.batch(listOf(first, first.copy(sequence = 2)), 1000).key)
    }
}
