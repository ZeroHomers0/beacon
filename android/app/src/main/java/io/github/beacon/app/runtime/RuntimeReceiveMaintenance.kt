package io.github.beacon.app.runtime

import android.content.Context
import io.github.beacon.app.data.local.CleanupPreferenceStore
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.receive.ReceiveMaintenancePolicy

private val maintenance = ReceiveMaintenancePolicy()

/** Runs after durable reception; maintenance failure never invalidates a received message. */
internal fun runReceiveCleanup(context: Context, store: RuntimeMessageStore) {
    try {
        val preferences = CleanupPreferenceStore(context)
        val settings = preferences.settings()
        if (settings.automatic && maintenance.due(android.os.SystemClock.elapsedRealtime())) {
            val changed = store.automaticCleanup(settings.policy).changed
            val time =
                io.github.beacon.app.domain.policy.DisplayTime.format(System.currentTimeMillis())
            preferences.record("$time · 已清理 $changed 条")
            maintenance.completed(android.os.SystemClock.elapsedRealtime())
        }
    } catch (_: Exception) {
        runCatching {
            CleanupPreferenceStore(context).record("自动清理未完成；下次成功接收后重试")
        }
    }
}

private object WalMaintenance {
    private const val INTERVAL_MS = 6 * 60 * 60_000L

    @Synchronized
    fun claim(context: Context): Boolean {
        val preferences =
            context.applicationContext.getSharedPreferences(
                "storage_maintenance",
                Context.MODE_PRIVATE,
            )
        val now = System.currentTimeMillis()
        val previous = preferences.getLong("wal_checkpoint_attempt_ms", 0L)
        if (previous > 0 && now - previous in 0 until INTERVAL_MS) return false
        return preferences.edit().putLong("wal_checkpoint_attempt_ms", now).commit()
    }
}
