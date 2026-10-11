package io.github.beacon.app.domain.model

/** A transient single-use secret; never place it in saved UI state or diagnostics. */
data class BindingRequest(val endpoint: String, val code: String, val deviceName: String) {
    fun validate(allowLoopback: Boolean = false) {
        TransportSettings(endpoint, "").validateForUpdate(allowLoopback)
        require(code.matches(Regex("[0-9a-f]{32}")))
        require(deviceName.toByteArray(Charsets.UTF_8).size <= 128)
    }
}

data class BoundDevice(
    val settings: TransportSettings,
    val serverId: String,
    val deviceId: String,
    val pushKey: String,
)
