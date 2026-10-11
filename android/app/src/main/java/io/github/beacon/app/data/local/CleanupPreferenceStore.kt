package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.CleanupPolicy
import io.github.beacon.app.domain.model.CleanupSettings

class CleanupPreferenceStore(context: Context) {
    private val preferences = GeneralPreferences.open(context)

    private fun loadPolicy(): CleanupPolicy {
        return CleanupPolicy(
                preferences.getInt("days", 30),
                preferences.getInt("max_messages", 10000),
                preferences.getBoolean("keep_unread", true),
            )
            .also { it.validate() }
    }

    fun settings() = runCatching {
        CleanupSettings(
            loadPolicy(),
            preferences.getBoolean("automatic", false),
            preferences.getString("last_result", "尚未自动清理") ?: "尚未自动清理",
        )
    }
        .getOrElse { CleanupSettings(lastResult = "清理配置读取失败，自动清理已停止；请重新保存规则") }

    fun record(result: String) {
        check(preferences.edit().putString("last_result", result).commit())
    }

    fun save(settings: CleanupSettings) {
        val policy = settings.policy
        policy.validate()
        check(
            preferences
                .edit()
                .putInt("days", policy.days)
                .putInt("max_messages", policy.maxMessages)
                .putBoolean("keep_unread", policy.keepUnread)
                .putBoolean("automatic", settings.automatic)
                .commit()
        )
    }
}
