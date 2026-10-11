package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.RotatedDeviceCredentials
import org.json.JSONObject

object RotatedCredentialsDecoder {
    private const val FIELD_NAMES = "server_id|device_id|receive_token|push_key"
    private val member = """"(?:$FIELD_NAMES)"\s*:\s*"[0-9a-f]{64}""""
    private val document = Regex("""\s*\{\s*$member(?:\s*,\s*$member){3}\s*\}\s*""")
    private val fields = Regex(""""($FIELD_NAMES)"\s*:""")

    fun decode(payload: String): RotatedDeviceCredentials {
        try {
            require(payload.length <= RotatedDeviceCredentials.MAX_PAYLOAD_BYTES)
            require(
                payload.toByteArray(Charsets.UTF_8).size <=
                    RotatedDeviceCredentials.MAX_PAYLOAD_BYTES
            )
            // Android's JSONObject accepts duplicate keys and non-JSON syntax. Gate the
            // four flat CLI fields before decoding, identically on JVM and Android.
            require(document.matches(payload))
            require(fields.findAll(payload).map { it.groupValues[1] }.toSet().size == 4)
            val body = JSONObject(payload)
            return RotatedDeviceCredentials(
                    body.getString("server_id"),
                    body.getString("device_id"),
                    body.getString("receive_token"),
                    body.getString("push_key"),
                )
                .also { it.validate() }
        } catch (_: Exception) {
            throw IllegalArgumentException("轮换结果格式无效，请粘贴服务器输出的完整 JSON")
        }
    }
}
