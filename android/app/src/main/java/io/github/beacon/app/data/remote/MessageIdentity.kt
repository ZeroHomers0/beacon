package io.github.beacon.app.data.remote

import java.security.MessageDigest

/** Identical storage identity across HTTP replay and realtime delivery. */
object MessageIdentity {
    private val id = Regex("[0-9a-f]{64}")

    fun valid(value: String): Boolean = id.matches(value)

    fun namespace(server: String, device: String): String {
        require(valid(server) && valid(device))
        return MessageDigest.getInstance("SHA-256")
            .digest("$server:$device".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
