package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.MessageLinkAction
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Strict, bounded link actions shared by transport, storage and history archives. */
object MessageActionsCodec {
    fun validate(actions: List<MessageLinkAction>) {
        require(actions.size <= 3)
        actions.forEach { action ->
            require(
                action.title.isNotBlank() && action.title.toByteArray(Charsets.UTF_8).size <= 64
            )
            require(action.title.none { Character.isISOControl(it) })
            require(action.url.toByteArray(Charsets.UTF_8).size <= 2048)
            require(
                action.url.none {
                    Character.isISOControl(it) || it.isWhitespace() || Character.isSpaceChar(it)
                }
            )
            val uri =
                try {
                    URI(action.url)
                } catch (e: java.net.URISyntaxException) {
                    throw IllegalArgumentException("Invalid action URL", e)
                }
            require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true))
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null)
            val host = requireNotNull(uri.host)
            if (!host.startsWith("[")) {
                val hostname = host.removeSuffix(".")
                require(hostname.length in 1..253)
                require(
                    hostname.split('.').all { label ->
                        label.length in 1..63 &&
                            label.first().isLetterOrDigit() &&
                            label.last().isLetterOrDigit() &&
                            label.all {
                                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-'
                            }
                    }
                )
            }
            require(uri.port == -1 || uri.port in 1..65535)
            require(uri.rawAuthority != null && !uri.rawAuthority.endsWith(":"))
        }
    }

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
        require(array.length() <= 3)
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
