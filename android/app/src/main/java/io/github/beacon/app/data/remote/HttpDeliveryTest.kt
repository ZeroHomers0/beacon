package io.github.beacon.app.data.remote

import io.github.beacon.app.BuildConfig
import io.github.beacon.app.data.local.ArchiveCrypto
import io.github.beacon.app.domain.model.BoundDevice
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Explicit self-push only. Never retry a POST whose server result is unknown. */
object HttpDeliveryTest {
    fun send(device: BoundDevice): String {
        device.settings.validate(BuildConfig.DEBUG)
        val connection =
            URL(device.settings.endpoint.trimEnd('/') + "/push").openConnection()
                as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes =
                JSONObject()
                    .put("device_key", device.pushKey)
                    .put("title", "Beacon 接收测试")
                    .put("body", "这是一条真实测试推送，用于检查服务器、消息保存与系统通知。")
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            check(connection.responseCode == 200)
            val reply =
                connection.inputStream.use {
                    JSONObject(ArchiveCrypto.readBounded(it, 16384).toString(Charsets.UTF_8))
                }
            require(reply.getInt("code") == 200)
            return reply.getString("id").also { require(MessageIdentity.valid(it)) }
        } finally {
            connection.disconnect()
        }
    }
}
