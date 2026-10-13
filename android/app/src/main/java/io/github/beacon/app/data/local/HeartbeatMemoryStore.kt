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

    fun save(key: String, seconds: Int) {
        if (load(key) == seconds) return
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
                    .put("at", System.currentTimeMillis())
                    .toString(),
            )
            .apply()
    }
}
