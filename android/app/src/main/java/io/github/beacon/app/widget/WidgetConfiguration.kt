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
        "list_columns" to 0,
        "tap_action" to "none", "short_labels" to JSONObject(),
    )

    /** 文字列表的字号默认「自动」（0）；其他模板仍是 16 号。 */
    private fun defaultTextSize(value: JSONObject): Int =
        if (value.optString("template") == "text_list") 0 else 16

    /** Restore only inherited visual defaults. Binding, content order, template and refresh stay intact. */
    fun resetAppearance(raw: String): JSONObject = JSONObject(raw).apply {
        listOf("color","background","bands","global_color","content_colors","number_scale",
            "label_scale","stroke_scale","text_size").forEach { remove(it) }
    }

    fun equivalent(first: String, second: String, format: String): Boolean =
        canonical(normalize(first, format)) == canonical(normalize(second, format))

    private fun normalize(raw: String, format: String): JSONObject {
        val value = JSONObject(raw)
        // text_size 的默认值取决于模板，必须在补默认值之前算出来。
        val textSize = defaultTextSize(value)
        defaults.forEach { (key, default) -> if (!value.has(key)) value.put(key, if (key == "text_size") textSize else default) }
        value.put("format", format)
        val colors = value.optJSONObject("content_colors") ?: JSONObject()
        colors.keys().asSequence().toList().forEach { if (colors.optString(it).isBlank()) colors.remove(it) }
        value.put("content_colors", colors)
        val labels=value.optJSONObject("short_labels")?:JSONObject()
        labels.keys().asSequence().toList().forEach{if(labels.optString(it).isBlank())labels.remove(it)}
        value.put("short_labels",labels)
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
