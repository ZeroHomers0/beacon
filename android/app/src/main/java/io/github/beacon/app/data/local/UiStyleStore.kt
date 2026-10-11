package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle

class UiStyleStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui_style_v1", Context.MODE_PRIVATE)

    fun load(): BeaconUiStyle = runCatching {
        BeaconUiStyle.valueOf(prefs.getString("style", BeaconUiStyle.MIUIX.name)!!)
    }
        .getOrDefault(BeaconUiStyle.MIUIX)

    fun save(style: BeaconUiStyle) {
        check(prefs.edit().putString("style", style.name).commit())
    }

    fun loadMode(): BeaconThemeMode = runCatching {
        BeaconThemeMode.valueOf(prefs.getString("mode", BeaconThemeMode.SYSTEM.name)!!)
    }
        .getOrDefault(BeaconThemeMode.SYSTEM)

    fun saveMode(mode: BeaconThemeMode) {
        check(prefs.edit().putString("mode", mode.name).commit())
    }
}
