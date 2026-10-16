package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.SyncOutcome
import org.json.JSONObject

/** Only the reception service serial executor reads or commits this preference file. */
class PollingStateStore(private val context: Context) {
    private val prefs = GeneralPreferences.open(context)

    fun load(): PollingState {
        val saved = prefs.getString("state", null)?.let(PollingWire::decode) ?: PollingState()
        return saved.copy(
            config = saved.config.copy(enabled = TransportSecretStore(context).isConfigured())
        )
    }

    fun save(state: PollingState) {
        state.config.validate()
        check(prefs.edit().putString("state", PollingWire.encode(state)).commit())
    }
}

object PollingWire {
    fun encode(state: PollingState): String =
        JSONObject()
            .put("enabled", state.config.enabled)
            .put("interval", state.config.intervalMinutes)
            .put("unmetered", state.config.unmetered)
            .put("charging", state.config.charging)
            .put("blocked", state.blocked)
            .put("attempt", state.lastAttemptMs)
            .put("success", state.lastSuccessMs)
            .put("revision", state.revision)
            .put("outcome", state.lastOutcome?.name ?: "")
            .put("successInserted", state.lastSuccessInserted)
            .toString()

    fun decode(json: String): PollingState {
        val value = JSONObject(json)
        val config =
            PollingConfig(
                value.getBoolean("enabled"),
                value.getInt("interval"),
                value.getBoolean("unmetered"),
                value.getBoolean("charging"),
            )
        config.validate()
        val outcome = SyncOutcome.entries.firstOrNull { it.name == value.optString("outcome", "") }
        return PollingState(
            config,
            value.getString("blocked"),
            value.getLong("attempt"),
            value.getLong("success"),
            value.getLong("revision"),
            outcome,
            if (value.has("successInserted")) value.getInt("successInserted").coerceAtLeast(0)
            else null,
        )
    }
}
