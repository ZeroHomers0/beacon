package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.QuietHours
import io.github.beacon.app.domain.model.QuietHoursBehavior

/** Reception components own these preferences. */
class QuietHoursStore(context: Context) {
    private val preferences = GeneralPreferences.open(context)

    private val defaults = QuietHours()

    fun load(): QuietHours =
        QuietHours(
                preferences.getBoolean("enabled", false),
                preferences.getInt("start", defaults.startMinute),
                preferences.getInt("end", defaults.endMinute),
                preferences.getInt("weekdays", defaults.weekdays),
                QuietHoursBehavior.valueOf(
                    preferences.getString("behavior", defaults.behavior.name)!!
                ),
            )
            .also { it.validate() }

    fun save(value: QuietHours) {
        value.validate()
        check(
            preferences
                .edit()
                .putBoolean("enabled", value.enabled)
                .putInt("start", value.startMinute)
                .putInt("end", value.endMinute)
                .putInt("weekdays", value.weekdays)
                .putString("behavior", value.behavior.name)
                .commit()
        )
    }
}
