package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.RealtimeFailure

internal object RealtimeFailureClassifier {
    fun classify(error: Throwable): RealtimeFailure {
        val chain = generateSequence(error) { it.cause }.take(10).toList()
        return when {
            chain.any { it is RealtimeAuthorizationFailure } -> RealtimeFailure.AUTH
            chain.any { it is java.security.cert.CertificateExpiredException } ->
                RealtimeFailure.CERTIFICATE_EXPIRED
            chain.any { it is java.security.cert.CertificateNotYetValidException } ->
                RealtimeFailure.CERTIFICATE_NOT_YET_VALID
            chain.any { it is javax.net.ssl.SSLPeerUnverifiedException } -> RealtimeFailure.HOSTNAME
            chain.any {
                it is java.security.cert.CertificateException ||
                    it is java.security.cert.CertPathValidatorException
            } -> RealtimeFailure.CERTIFICATE_UNTRUSTED
            chain.any { it is java.net.SocketTimeoutException } -> RealtimeFailure.TIMEOUT
            chain.any { it is java.net.UnknownHostException } -> RealtimeFailure.DNS
            chain.any { it is javax.net.ssl.SSLException } -> RealtimeFailure.TLS_INTERRUPTED
            chain.any { it is java.net.ConnectException } -> RealtimeFailure.CONNECTION
            chain.any { it is IllegalArgumentException } -> RealtimeFailure.CONFIG
            chain.any { it is java.io.IOException } -> RealtimeFailure.NETWORK
            else -> RealtimeFailure.INTERNAL
        }
    }
}
