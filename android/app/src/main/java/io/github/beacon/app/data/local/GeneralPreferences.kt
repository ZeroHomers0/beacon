package io.github.beacon.app.data.local

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Runtime-owned, one-file commits allow an imported settings snapshot to be atomic. */
internal object GeneralPreferences {
    private val sources =
        listOf(
            "polling_state_v1",
            "receive_plan_v1",
            "notification_settings",
            "notification_quiet_hours",
            "storage_cleanup",
        )

    @Synchronized
    fun open(context: Context): SharedPreferences {
        val result = context.getSharedPreferences("general_settings_v1", Context.MODE_PRIVATE)
        if (!result.getBoolean("migrated", false)) {
            val editor = result.edit()
            val seen = mutableSetOf<String>()
            sources.forEach { source ->
                val legacy = context.getSharedPreferences(source, Context.MODE_PRIVATE)
                if (legacy === result) return@forEach
                legacy.all.forEach { (key, value) ->
                    check(seen.add(key)) { "Overlapping settings key" }
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        else -> error("Unsupported settings value")
                    }
                }
            }
            check(editor.putBoolean("migrated", true).commit())
        }
        if (!result.getBoolean("rollback_guarded", false)) {
            // An older APK reads the legacy files. Keep its receive/cleanup switches off so
            // rolling back cannot revive a state that the user paused after this migration.
            val polling = context.getSharedPreferences("polling_state_v1", Context.MODE_PRIVATE)
            val cleanup = context.getSharedPreferences("storage_cleanup", Context.MODE_PRIVATE)
            if (polling !== result) {
                val legacy = polling.getString("state", null)
                if (legacy != null)
                    check(
                        polling
                            .edit()
                            .putString("state", JSONObject(legacy).put("enabled", false).toString())
                            .commit()
                    )
            }
            if (cleanup !== result) check(cleanup.edit().putBoolean("automatic", false).commit())
            check(result.edit().putBoolean("rollback_guarded", true).commit())
        }
        return result
    }
}
