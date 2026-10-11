package io.github.beacon.app.data.remote

import io.github.beacon.app.BuildConfig
import io.github.beacon.app.domain.model.BoundDevice
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.json.JSONObject

class DeviceRevocationRejected(val status: Int) : IOException()

class DeviceRevocationUnconfirmed : IOException()

/** No redirects. A lost response keeps local credentials so this idempotent request can retry. */
class HttpDeviceRevocation {
    fun revoke(
        device: BoundDevice,
        deadlineNanos: Long = System.nanoTime() + 30_000_000_000L,
        active: () -> Boolean = { true },
    ) {
        device.settings.validate(BuildConfig.DEBUG)
        require(MessageIdentity.valid(device.serverId) && MessageIdentity.valid(device.deviceId))
        fun remainingMs(): Int {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (!active() || remaining <= 0) throw SocketTimeoutException()
            return remaining.coerceAtMost(5000).toInt()
        }
        remainingMs()
        val connection =
            URL(device.settings.endpoint.trimEnd('/') + "/api/v1/device/revoke").openConnection()
                as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = remainingMs()
            connection.readTimeout = remainingMs()
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${device.settings.token}")
            val body =
                JSONObject()
                    .put("server_id", device.serverId)
                    .put("device_id", device.deviceId)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use {
                remainingMs()
                it.write(body)
            }
            connection.readTimeout = remainingMs()
            if (connection.responseCode != 200)
                throw DeviceRevocationRejected(connection.responseCode)
            val bytes =
                connection.inputStream.use { input ->
                    val result = ByteArrayOutputStream()
                    val block = ByteArray(1024)
                    while (true) {
                        connection.readTimeout = remainingMs()
                        val count = input.read(block)
                        if (count < 0) break
                        if (result.size() + count > MAX_RESPONSE_BYTES)
                            throw DeviceRevocationUnconfirmed()
                        result.write(block, 0, count)
                    }
                    result.toByteArray()
                }
            remainingMs()
            verifyConfirmation(String(bytes, Charsets.UTF_8), device.serverId, device.deviceId)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 4096
        private val member =
            """(?:"revoked"\s*:\s*true|"(?:server_id|device_id)"\s*:\s*"[0-9a-f]{64}")"""
        private val document = Regex("""\s*\{\s*$member(?:\s*,\s*$member){2}\s*\}\s*""")
        private val fields = Regex(""""(revoked|server_id|device_id)"\s*:""")

        internal fun verifyConfirmation(payload: String, serverId: String, deviceId: String) {
            try {
                require(payload.length <= MAX_RESPONSE_BYTES)
                require(payload.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
                // JSONObject is permissive on Android; reject duplicates and non-JSON syntax first.
                require(document.matches(payload))
                require(fields.findAll(payload).map { it.groupValues[1] }.toSet().size == 3)
                val body = JSONObject(payload)
                require(body.get("revoked") == true)
                require(body.get("server_id") == serverId && body.get("device_id") == deviceId)
            } catch (_: Exception) {
                throw DeviceRevocationUnconfirmed()
            }
        }
    }
}
