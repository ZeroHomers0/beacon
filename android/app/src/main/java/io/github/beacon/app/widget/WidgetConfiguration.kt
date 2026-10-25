package io.github.beacon.app.widget

import org.json.JSONArray
import org.json.JSONObject

/** Compare effective settings, including defaults omitted by older widget configurations. */
internal object WidgetConfiguration {
    private val defaults = mapOf(
        "color" to "fixed", "background" to "system", "instance_name" to "", "title" to "",
        "source" to "", "identity" to "", "refresh" to "balanced", "updated" to true,
        "bands" to false, "global_color" to "", "number_scale" to 100, "label_scale" to 100,
        "stroke_scale" to 100, "decimals" to -1, "unit_position" to "after", "trend" to true,
        "text_size" to 16, "list_start" to 1, "list_count" to 12, "list_lines" to 1, "list_rank" to true,
    )

    /** Restore only inherited visual defaults. Binding, content order, template and refresh stay intact. */
    fun resetAppearance(raw: String): JSONObject = JSONObject(raw).apply {
        listOf("color","background","bands","global_color","content_colors","number_scale",
            "label_scale","stroke_scale","text_size").forEach { remove(it) }
    }

    fun equivalent(first: String, second: String, format: String): Boolean =
        canonical(normalize(first, format)) == canonical(normalize(second, format))

    private fun normalize(raw: String, format: String): JSONObject {
        val value = JSONObject(raw)
        defaults.forEach { (key, default) -> if (!value.has(key)) value.put(key, default) }
        value.put("format", format)
        val colors = value.optJSONObject("content_colors") ?: JSONObject()
        colors.keys().asSequence().toList().forEach { if (colors.optString(it).isBlank()) colors.remove(it) }
        value.put("content_colors", colors)
        return value
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix="{", postfix="}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix="[", postfix="]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
