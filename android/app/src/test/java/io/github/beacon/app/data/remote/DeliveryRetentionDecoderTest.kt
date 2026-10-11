package io.github.beacon.app.data.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeliveryRetentionDecoderTest {
    @Test
    fun oldServersPreserveRecordsAndNewContractIsStrict() {
        assertNull(DeliveryRetentionDecoder.cutoff(JSONObject("{}")))
        val contract =
            JSONObject()
                .put("delivery_retention_protocol", 1)
                .put("delivery_window_ms", 604800000L)
                .put("receipt_window_ms", 2592000000L)
                .put("server_time_ms", 1800000000000L)
        assertEquals(1796802600000L, DeliveryRetentionDecoder.cutoff(contract))
        contract.put("receipt_window_ms", "2592000000")
        assertTrue(runCatching { DeliveryRetentionDecoder.cutoff(contract) }.isFailure)
        contract.put("receipt_window_ms", Long.MAX_VALUE)
        assertTrue(runCatching { DeliveryRetentionDecoder.cutoff(contract) }.isFailure)
    }
}
