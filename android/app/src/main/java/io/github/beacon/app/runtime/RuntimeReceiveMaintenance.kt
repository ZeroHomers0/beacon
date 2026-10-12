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
            val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
            preferences.record("$time · 已清理 $changed 条本地消息")
            maintenance.completed(android.os.SystemClock.elapsedRealtime())
        }
    } catch (_: Exception) {
        runCatching {
            CleanupPreferenceStore(context).record("自动清理未完成；下次成功接收后重试")
        }
    }
}
