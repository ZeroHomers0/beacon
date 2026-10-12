package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.receive.*
import org.json.JSONException
import org.json.JSONObject

internal data class HttpMessagePage(
    val messages: List<IncomingMessage>,
    val cursor: Long,
    val hasMore: Boolean,
)

/** Validate the complete page before saving or confirming any of it. */
internal object HttpMessagePageDecoder {
    private fun integer(value: Any?): Long {
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }

    fun decode(
        page: JSONObject,
        server: String,
        namespace: String,
        after: Long,
        serverTime: Long,
        receivedTime: Long,
    ): HttpMessagePage =
        try {
            require(page.get("server_id") == server)
            val rows = page.getJSONArray("messages")
            require(rows.length() <= HttpSyncLimits.PAGE_SIZE)
            var previous = after
            val ids = mutableSetOf<String>()
            val messages =
                (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    val sequence = integer(row.get("sequence"))
                    require(sequence > previous)
                    previous = sequence
                    val id = row.get("id")
                    require(id is String && MessageIdentity.valid(id) && ids.add(id))
                    val title = row.get("title")
                    val body = row.get("body")
                    require(title is String && body is String)
                    val subtitle = row.opt("subtitle")
                    require(subtitle == null || subtitle === JSONObject.NULL || subtitle is String)
                    val created = row.opt("created_at_ms")
                    IncomingMessage(
                            namespace,
                            id,
                            title,
                            body,
                            NotificationPolicy.normalizedTimestamp(
                                if (created == null || created === JSONObject.NULL) 0
                                else integer(created),
                                serverTime,
                                receivedTime,
                            ),
                            subtitle = if (subtitle is String) subtitle else "",
                            priority = MessagePriorityDecoder.decode(row),
                            applicationId = MessageMetadataDecoder.application(row),
                            applicationName = MessageMetadataDecoder.name(row),
                            group = MessageMetadataDecoder.group(row),
                            expiresAtMs = MessageMetadataDecoder.expires(row),
                            bark =
                                (row.opt("bark") ?: "").also {
                                    require(
                                        it is String &&
                                            it.toByteArray(Charsets.UTF_8).size <= 100000
                                    )
                                } as String,
                        )
                        .also(ReceiveMessageValidation::validate)
                }
            val cursor = integer(page.get("next_cursor"))
            require(cursor == previous)
            val more = page.get("has_more")
            require(more is Boolean && (!more || messages.isNotEmpty()))
            HttpMessagePage(messages, cursor, more)
        } catch (_: JSONException) {
            throw SyncResponseException(SyncStage.MESSAGES)
        } catch (_: IllegalArgumentException) {
            throw SyncResponseException(SyncStage.MESSAGES)
        }
}
