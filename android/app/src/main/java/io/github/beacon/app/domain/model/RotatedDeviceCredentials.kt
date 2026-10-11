package io.github.beacon.app.domain.model

/** Transient secrets: never saved in UI state, logs, reports or navigation arguments. */
class RotatedDeviceCredentials(
    val serverId: String,
    val deviceId: String,
    val receiveToken: String,
    val pushKey: String,
) {
    fun validate() {
        require(
            listOf(serverId, deviceId, receiveToken, pushKey).all {
                it.matches(Regex("[0-9a-f]{64}"))
            }
        )
        require(receiveToken != pushKey)
    }

    override fun toString() = "RotatedDeviceCredentials(redacted)"

    companion object {
        const val MAX_PAYLOAD_BYTES = 4096
    }
}
