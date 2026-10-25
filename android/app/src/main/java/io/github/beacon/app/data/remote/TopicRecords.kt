package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.TopicIdentity
import io.github.beacon.app.domain.model.TopicSnapshot
import org.json.JSONObject

/** JSON compatibility belongs at this edge, not in the framework-free domain model. */
fun JSONObject.topicSnapshot() = TopicSnapshot(
    identity = TopicIdentity(optString("local_source"), optString("application_id"), optString("topic_key")),
    waiting = optBoolean("_waiting"),
    directoryDeleted = optBoolean("_topic_deleted"),
    cleared = optBoolean("deleted"),
    staleAtMs = optLong("stale_at_ms"),
    expiresAtMs = optLong("expires_at_ms"),
)
