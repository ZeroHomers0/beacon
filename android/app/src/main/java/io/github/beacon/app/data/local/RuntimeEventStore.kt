package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Only the runtime serial executor accesses this bounded, allowlisted journal. */
class RuntimeEventStore(context: Context) {
    private val prefs = context.getSharedPreferences("runtime_events_v1", Context.MODE_PRIVATE)

    fun read(): List<RuntimeEvent> {
        val raw = prefs.getString("events", null) ?: return emptyList()
        require(raw.length <= MAX_JSON_CHARACTERS)
        val array = JSONArray(raw)
        require(array.length() <= RuntimeEvent.MAX_EVENTS)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RuntimeEvent(
                    item.getLong("at"),
                    requireNotNull(RuntimeEventKind.fromWireId(item.getInt("kind"))),
                    item.getInt("outcome").let {
                        if (it == -1) null else requireNotNull(SyncOutcome.fromWireId(it))
                    },
                    RealtimeFailure.valueOf(item.getString("failure")),
                )
                .also { it.validate() }
        }
    }

    /**
     * The journal is a record of transitions that were already acted on, never an input to
     * recovery: the bounded budget lives in [CrashRecoveryStore] and keeps its synchronous commit.
     * So this write may be asynchronous. `commit` fsyncs the whole preference file on the runtime
     * serial executor for every event, and it cannot coalesce; `apply` updates the in-memory map
     * immediately — so a later [read] on this or any other instance in the process still sees the
     * event — and hands the file write to the platform, which merges back-to-back edits into one. A
     * process death in that window can drop the most recent events, which is why nothing that gates
     * reception may be journalled through here. The caller already tolerates failure.
     */
    fun append(event: RuntimeEvent) {
        val history =
            try {
                read()
            } catch (_: Exception) {
                emptyList()
            }
        val array = JSONArray()
        RuntimeEventHistory.append(history, event).forEach {
            array.put(
                JSONObject()
                    .put("at", it.atMs)
                    .put("kind", it.kind.wireId)
                    .put("outcome", it.outcome?.wireId ?: -1)
                    .put("failure", it.failure.name)
            )
        }
        prefs.edit().putString("events", array.toString()).apply()
    }

    fun clear() {
        check(prefs.edit().remove("events").commit())
    }

    companion object {
        private const val MAX_JSON_CHARACTERS = 32 * 1024
    }
}
