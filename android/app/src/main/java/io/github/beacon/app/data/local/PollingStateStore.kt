package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.domain.policy.SyncResultText
import org.json.JSONObject

/** Only the :runtime serial executor reads or commits this preference file. */
class PollingStateStore(context: Context) {
    private val prefs = GeneralPreferences.open(context)

    fun load(): PollingState =
        prefs.getString("state", null)?.let(PollingWire::decode) ?: PollingState()

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
            .put("result", state.lastResult)
            .put("revision", state.revision)
            .put("outcome", state.lastOutcome?.name ?: "")
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
            SyncResultText.normalize(value.getString("result")),
            value.getLong("revision"),
            outcome,
        )
    }
}
