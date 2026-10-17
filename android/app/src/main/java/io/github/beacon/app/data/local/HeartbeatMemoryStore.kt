package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.receive.AdaptiveHeartbeatPolicy
import org.json.JSONObject

/** Local, bounded hints. Network details and credentials are never stored or backed up. */
internal class HeartbeatMemoryStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("heartbeat_memory_v1", Context.MODE_PRIVATE)

    fun load(key: String): Int = runCatching {
        val record = JSONObject(preferences.getString(key, "{}")!!)
        val age = System.currentTimeMillis() - record.getLong("at")
        if (age !in 0..7 * 24 * 60 * 60_000L) AdaptiveHeartbeatPolicy.MIN_SECONDS
        else record.getInt("seconds").coerceIn(60, 300)
    }
        .getOrDefault(AdaptiveHeartbeatPolicy.MIN_SECONDS)

    fun failure(key: String): Pair<Int, Long> = runCatching {
        val record = JSONObject(preferences.getString(key, "{}")!!)
        val until = record.optLong("retry_after")
        val remaining = until - System.currentTimeMillis()
        if (remaining in 1..AdaptiveHeartbeatPolicy.FAILURE_COOLDOWN_MS)
            record.optInt("failed_seconds") to until
        else 0 to 0L
    }.getOrDefault(0 to 0L)

    fun save(key: String, seconds: Int, failedSeconds: Int = 0, retryAfterMs: Long = 0) {
        if (load(key) == seconds && failure(key) == (failedSeconds to retryAfterMs)) return
        val editor = preferences.edit()
        if (!preferences.contains(key) && preferences.all.size >= 16) {
            val oldest =
                preferences.all.keys.minByOrNull {
                    runCatching { JSONObject(preferences.getString(it, "{}")!!).getLong("at") }
                        .getOrDefault(0)
                }
            oldest?.let { editor.remove(it) }
        }
        editor
            .putString(
                key,
                JSONObject()
                    .put("seconds", seconds)
                    .put("failed_seconds", failedSeconds)
                    .put("retry_after", retryAfterMs)
                    .put("at", System.currentTimeMillis())
                    .toString(),
            )
            .apply()
    }
}
