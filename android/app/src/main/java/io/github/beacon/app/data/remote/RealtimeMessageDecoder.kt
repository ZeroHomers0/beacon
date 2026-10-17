package io.github.beacon.app.data.remote

import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageLimits
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

/** Untrusted stream payload decoder; never advances the HTTP history cursor. */
class RealtimeMessageDecoder(
    private val server: String,
    device: String,
    private val clockOffsetMs: Long? = null,
) {
    private val namespace = MessageIdentity.namespace(server, device)

    fun decode(payload: ByteArray, receivedAtMs: Long): IncomingMessage {
        require(payload.size in 1..MAX_PAYLOAD_BYTES)
        require(receivedAtMs > 0)
        val text =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        val envelope = JSONObject(text)
        require(envelope.opt("protocol") == 1)
        require(envelope.opt("server_id") == server)
        val row = envelope.getJSONObject("message")
        val id = row.opt("id")
        require(id is String && MessageIdentity.valid(id))
        val sequence = row.opt("sequence")
        require(
            sequence is Number && (sequence is Long || sequence is Int) && sequence.toLong() > 0
        )
        val timestamp = row.opt("created_at_ms")
        require(
            timestamp is Number && (timestamp is Long || timestamp is Int) && timestamp.toLong() > 0
        )
        val title = string(row, "title", MessageLimits.TITLE_BYTES)
        val body = string(row, "body", MessageLimits.BODY_BYTES)
        val subtitle =
            if (!row.has("subtitle") || row.isNull("subtitle")) ""
            else string(row, "subtitle", MessageLimits.TITLE_BYTES)
        return IncomingMessage(
            namespace,
            id,
            title,
            body,
            clockOffsetMs?.let { offset ->
                val source = timestamp.toLong()
                if (offset in -300_000L..300_000L && source <= Long.MAX_VALUE - 300_000L)
                    (source + offset).takeIf { it in 1..receivedAtMs } ?: 0L
                else 0L
            } ?: 0L,
            receivedAtMs,
            subtitle = subtitle,
            priority = MessagePriorityDecoder.decode(row),
            actions = MessageActionsCodec.decode(row),
            applicationId = MessageMetadataDecoder.application(row),
            applicationName = MessageMetadataDecoder.name(row),
            group = MessageMetadataDecoder.group(row),
            expiresAtMs = MessageMetadataDecoder.expires(row),
            bark =
                (row.opt("bark") ?: "").also {
                    require(it is String && it.toByteArray(Charsets.UTF_8).size <= 100000)
                } as String,
        ).also(io.github.beacon.app.domain.receive.ReceiveMessageValidation::validate)
    }

    private fun string(row: JSONObject, key: String, limit: Int): String {
        val value = row.opt(key)
        require(value is String && value.toByteArray(Charsets.UTF_8).size <= limit)
        return value
    }

    companion object {
        // JSON escaping can expand a valid 64 KiB body by six times.
        const val MAX_PAYLOAD_BYTES = 1024 * 1024
    }
}
