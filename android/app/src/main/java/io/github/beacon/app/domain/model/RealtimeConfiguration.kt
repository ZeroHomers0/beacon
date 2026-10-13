package io.github.beacon.app.domain.model

import java.net.URI

/** Authenticated same-origin WebSocket metadata. */
data class RealtimeConfiguration(
    val serverId: String,
    val deviceId: String,
    val streamUrl: String,
    val clockOffsetMs: Long? = null,
    val adaptiveHeartbeatSupported: Boolean = false,
) {
    fun validate() {
        require(listOf(serverId, deviceId).all { it.matches(Regex("[0-9a-f]{64}")) })
        val uri = URI(streamUrl)
        require(uri.scheme == "wss" && uri.host != null && streamUrl.length <= 512)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        require(uri.rawPath.endsWith("/api/v1/realtime/stream"))
    }
}
