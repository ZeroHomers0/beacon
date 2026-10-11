package io.github.beacon.app.feature.settings

import androidx.compose.runtime.*

/** Unsaved input belongs to presentation, never to the transport credentials store. */
class ConnectionFormState {
    var endpoint by mutableStateOf("")
    var bindingCode by mutableStateOf("")
    var deviceName by mutableStateOf(android.os.Build.MODEL)
    var connectionEdited by mutableStateOf(false)
    var connectionError by mutableStateOf("")

    /** Transient confirmation that the push key reached the clipboard. */
    var copiedPushKey by mutableStateOf(false)
}
