package io.github.beacon.app.feature.messages

import io.github.beacon.app.domain.model.DataStatus
import io.github.beacon.app.domain.policy.MessageMarkdown
import org.json.JSONObject

internal data class TopicListPreview(val summary: String, val progress: Float? = null, val numeric: Boolean = false)

/** Show a real numeric block when present; missing/invalid data must never become zero. */
internal fun topicListPreview(row: JSONObject, status: DataStatus): TopicListPreview {
    if (status in setOf(DataStatus.MISSING, DataStatus.WAITING, DataStatus.CLEARED)) return TopicListPreview("")
    val blocks = row.optJSONArray("blocks")
    val block = (0 until (blocks?.length() ?: 0)).mapNotNull { blocks?.optJSONObject(it) }
        .firstOrNull { it.optString("kind") in listOf("metric", "progress") && !it.optBoolean("indeterminate") && it.optDouble("value").isFinite() }
    if (block != null) {
        val value = block.optDouble("value")
        val number = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toString()
        val min = block.optDouble("min", 0.0)
        val max = block.optDouble("max")
        val progress = if (min.isFinite() && max.isFinite() && max > min)
            ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f) else null
        return TopicListPreview(listOf(block.optString("label"), number + block.optString("unit"))
            .filter { it.isNotBlank() }.joinToString(" · "), progress, true)
    }
    return TopicListPreview(MessageMarkdown.summary(row.optString("body")))
}
