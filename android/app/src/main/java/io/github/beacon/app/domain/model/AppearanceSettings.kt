package io.github.beacon.app.domain.model

/** Names are persisted in preferences; keep them stable across upgrades. */
enum class BeaconUiStyle {
    MATERIAL3,
    MIUIX,
}

enum class BeaconThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}
