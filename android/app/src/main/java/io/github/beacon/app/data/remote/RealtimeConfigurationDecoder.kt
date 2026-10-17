package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.ProtocolContract
import io.github.beacon.app.domain.model.RealtimeConfiguration
import java.net.URI
import org.json.JSONObject

object RealtimeConfigurationDecoder {
    fun decode(json: String, endpoint: String? = null): RealtimeConfiguration {
        val root = JSONObject(json)
        val protocol = root.opt("protocol")
        require(protocol is Int && ProtocolContract.realtimeSupported(protocol))
        require(
            root.keys().asSequence().toSet() ==
                setOf("protocol", "server_id", "device_id", "stream_path")
        )
        require(root.get("stream_path") == "/api/v1/realtime/stream")
        val base = URI(requireNotNull(endpoint))
        require(
            base.scheme == "https" &&
                base.host != null &&
                base.rawUserInfo == null &&
                base.rawQuery == null &&
                base.rawFragment == null
        )
        return RealtimeConfiguration(
                root.get("server_id") as String,
                root.get("device_id") as String,
                "wss://" +
                    base.rawAuthority +
                    base.rawPath.trimEnd('/') +
                    root.getString("stream_path"),
            )
            .also { it.validate() }
    }
}
