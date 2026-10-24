package io.github.beacon.app.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.beacon.app.domain.model.CleanupSettings

/** Last confirmed snapshot survives navigation; the runtime remains the source of truth. */
class StorageSettingsState {
    var snapshot by mutableStateOf<CleanupSettings?>(null)
}
