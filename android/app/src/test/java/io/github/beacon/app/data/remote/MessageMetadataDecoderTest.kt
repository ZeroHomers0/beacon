package io.github.beacon.app.data.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageMetadataDecoderTest {
    @Test fun oldServerFieldsAreOptional() {
        val row = JSONObject()
        assertEquals("", MessageMetadataDecoder.application(row));assertEquals("", MessageMetadataDecoder.group(row));assertEquals(0L, MessageMetadataDecoder.expires(row))
    }
    @Test fun rejectsMalformedIdentityAndOversizedUtf8Metadata() {
        assertThrows(IllegalArgumentException::class.java) { MessageMetadataDecoder.application(JSONObject().put("application_id", "bad")) }
        assertThrows(IllegalArgumentException::class.java) { MessageMetadataDecoder.group(JSONObject().put("group", "中".repeat(43))) }
        assertThrows(IllegalArgumentException::class.java) { MessageMetadataDecoder.expires(JSONObject().put("expires_at_ms", "60000")) }
        assertThrows(IllegalArgumentException::class.java) { MessageMetadataDecoder.name(JSONObject().put("application_name", "中".repeat(43))) }
    }
}
