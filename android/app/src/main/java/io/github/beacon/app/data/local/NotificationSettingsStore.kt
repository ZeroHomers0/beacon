package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.domain.model.OutdatedReminder

/** Runtime-owned preferences, separate from credentials and message retention. */
class NotificationSettingsStore(context: Context) {
    private val preferences = GeneralPreferences.open(context)
    private val defaults = NotificationSettings()

    fun load(): NotificationSettings =
        NotificationSettings(
                preferences.getInt("fresh_minutes", defaults.freshMinutes),
                OutdatedReminder.valueOf(
                    requireNotNull(
                        preferences.getString("outdated", defaults.outdatedReminder.name)
                    )
                ),
                preferences.getBoolean("lockscreen_preview", defaults.lockscreenPreview),
            )
            .also { it.validate() }

    fun save(value: NotificationSettings) {
        value.validate()
        check(
            preferences
                .edit()
                .putInt("fresh_minutes", value.freshMinutes)
                .putString("outdated", value.outdatedReminder.name)
                .putBoolean("lockscreen_preview", value.lockscreenPreview)
                .commit()
        )
    }
}
