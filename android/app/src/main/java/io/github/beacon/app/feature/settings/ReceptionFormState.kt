package io.github.beacon.app.feature.settings

import androidx.compose.runtime.*
import io.github.beacon.app.domain.model.PollingConfig

/** Keep unsaved power-saving settings when the user opens the mode picker. */
class ReceptionFormState {
    var interval by mutableIntStateOf(15)
    var unmetered by mutableStateOf(false)
    var charging by mutableStateOf(false)
    var edited by mutableStateOf(false)

    fun reset(config: PollingConfig) {
        interval = config.intervalMinutes
        unmetered = config.unmetered
        charging = config.charging
        edited = false
    }

    fun differsFrom(config: PollingConfig): Boolean =
        interval != config.intervalMinutes ||
            unmetered != config.unmetered ||
            charging != config.charging

    fun applyTo(config: PollingConfig): PollingConfig =
        config.copy(intervalMinutes = interval, unmetered = unmetered, charging = charging)
}
