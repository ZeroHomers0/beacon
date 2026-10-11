package io.github.beacon.app.data.remote

import io.github.beacon.app.BuildConfig
import io.github.beacon.app.domain.model.BindingRequest
import io.github.beacon.app.domain.model.BoundDevice
import io.github.beacon.app.domain.model.TransportSettings
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class BindingRejected(val status: Int) : java.io.IOException()

class BindingResponseInvalid : java.io.IOException()

/** No redirects or automatic retries: a successful exchange consumes the one-time code. */
class HttpDeviceBinding {
    fun bind(
        request: BindingRequest,
        deadlineNanos: Long = System.nanoTime() + 30_000_000_000L,
    ): BoundDevice {
        request.validate(BuildConfig.DEBUG)
        fun remainingMs(): Int {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw java.net.SocketTimeoutException()
            return remaining.coerceAtMost(5000).toInt()
        }
        remainingMs()
        val connection =
            URL(request.endpoint.trimEnd('/') + "/api/v1/bind").openConnection()
                as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val payload =
                JSONObject()
                    .put("binding_code", request.code)
                    .put("device_name", request.deviceName)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 201) throw BindingRejected(connection.responseCode)
            val bytes =
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val block = ByteArray(4096)
                    while (true) {
                        connection.readTimeout = remainingMs()
                        val count = input.read(block)
                        if (count < 0) break
                        if (output.size() + count > 16 * 1024) throw BindingResponseInvalid()
                        output.write(block, 0, count)
                    }
                    output.toByteArray()
                }
            return try {
                val body = JSONObject(String(bytes, Charsets.UTF_8))
                if (body.getInt("protocol") != 1) throw BindingResponseInvalid()
                val device =
                    BoundDevice(
                        TransportSettings(request.endpoint, body.getString("receive_token")),
                        body.getString("server_id"),
                        body.getString("device_id"),
                        body.getString("push_key"),
                    )
                device.settings.validate(BuildConfig.DEBUG)
                if (
                    listOf(device.serverId, device.deviceId, device.pushKey).any {
                        !it.matches(Regex("[0-9a-f]{64}"))
                    } || device.pushKey == device.settings.token
                )
                    throw BindingResponseInvalid()
                remainingMs()
                device
            } catch (_: Exception) {
                throw BindingResponseInvalid()
            }
        } finally {
            connection.disconnect()
        }
    }
}
