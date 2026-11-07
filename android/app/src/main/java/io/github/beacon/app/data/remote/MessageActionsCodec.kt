package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.MessageLinkAction
import io.github.beacon.app.domain.model.MessageLimits
import io.github.beacon.app.domain.policy.MessageActionValidation.validate
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Strict, bounded link actions shared by transport, storage and history archives. */
object MessageActionsCodec {
    fun encode(actions: List<MessageLinkAction>): String {
        validate(actions)
        return JSONArray()
            .apply {
                actions.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
            }
            .toString()
    }

    fun decode(row: JSONObject): List<MessageLinkAction> =
        if (!row.has("actions")) emptyList() else decode(row.getJSONArray("actions"))

    fun decode(text: String): List<MessageLinkAction> {
        require(text.length <= 40000)
        val tokenizer = JSONTokener(text)
        val array = JSONArray(tokenizer)
        require(tokenizer.nextClean() == '\u0000')
        return decode(array)
    }

    fun decode(array: JSONArray): List<MessageLinkAction> {
        require(array.length() <= MessageLimits.ACTION_COUNT)
        return (0 until array.length())
            .map { index ->
                val row = array.getJSONObject(index)
                require(row.length() == 2 && row.has("title") && row.has("url"))
                val title = row.get("title")
                val url = row.get("url")
                require(title is String && url is String)
                MessageLinkAction(title, url)
            }
            .also(::validate)
    }
}
