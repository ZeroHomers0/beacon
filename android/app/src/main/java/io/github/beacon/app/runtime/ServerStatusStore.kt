package io.github.beacon.app.runtime

import android.content.Context
import androidx.core.content.edit

/**
 * Last server probe, kept in the runtime process so a fresh UI process can show the previous
 * verdict instead of flashing "checking" on every entry. Stored in shared preferences because the
 * runtime may be restarted by the system while the UI is gone; the answer is still the truth of the
 * last successful round trip.
 */
internal class ServerStatusStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("server_status", Context.MODE_PRIVATE)

    data class Verdict(
        val endpoint: String,
        val reachable: Boolean,
        val message: String,
        val checkedAtMs: Long,
    )

    fun load(): Verdict? {
        val endpoint = preferences.getString(KEY_ENDPOINT, null) ?: return null
        val message = preferences.getString(KEY_MESSAGE, null) ?: return null
        val checkedAt = preferences.getLong(KEY_CHECKED_AT, 0L)
        if (checkedAt <= 0L) return null
        return Verdict(
            endpoint = endpoint,
            reachable = preferences.getBoolean(KEY_REACHABLE, false),
            message = message,
            checkedAtMs = checkedAt,
        )
    }

    fun save(endpoint: String, reachable: Boolean, message: String, checkedAtMs: Long) {
        preferences.edit {
            putString(KEY_ENDPOINT, endpoint)
            putBoolean(KEY_REACHABLE, reachable)
            putString(KEY_MESSAGE, message)
            putLong(KEY_CHECKED_AT, checkedAtMs)
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_REACHABLE = "reachable"
        const val KEY_MESSAGE = "message"
        const val KEY_CHECKED_AT = "checked_at_ms"
    }
}
