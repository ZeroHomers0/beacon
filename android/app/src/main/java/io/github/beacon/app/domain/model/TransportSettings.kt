package io.github.beacon.app.domain.model

import java.net.URI

data class TransportSettings(val endpoint: String, val token: String) {
    fun resolved(allowLoopback: Boolean = false, savedToken: () -> String): TransportSettings =
        copy(token = token.ifBlank(savedToken)).also { it.validate(allowLoopback) }

    fun validate(allowLoopback: Boolean = false) {
        validateForUpdate(allowLoopback)
        require(token.matches(Regex("[0-9a-f]{64}")))
    }

    /** Empty input means retain the worker's encrypted credential, never an empty saved secret. */
    fun validateForUpdate(allowLoopback: Boolean = false) {
        val uri = URI(endpoint)
        require(
            uri.host != null &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        )
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        require(
            uri.scheme == "https" ||
                (allowLoopback &&
                    uri.scheme == "http" &&
                    uri.host in listOf("127.0.0.1", "localhost"))
        )
        require(token.isEmpty() || token.matches(Regex("[0-9a-f]{64}")))
    }
}
