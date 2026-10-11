package io.github.beacon.app.domain.receive

import io.github.beacon.app.data.local.PollingWire
import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.SyncOutcome
import org.junit.Assert.*
import org.junit.Test

class PollingPolicyTest {
    @Test
    fun legacyStateHasUnknownOutcomeAndNewOutcomeSurvivesRoundTrip() {
        val old =
            org.json.JSONObject(PollingWire.encode(PollingState())).apply { remove("outcome") }
        assertNull(PollingWire.decode(old.toString()).lastOutcome)
        old.put("outcome", "untrusted-token")
        assertNull(PollingWire.decode(old.toString()).lastOutcome)
        val current = PollingState(lastOutcome = SyncOutcome.AUTH)
        assertEquals(current, PollingWire.decode(PollingWire.encode(current)))
    }

    @Test
    fun intervalMustMatchSystemMinimumAndMaximum() {
        PollingConfig(intervalMinutes = 15).validate()
        PollingConfig(intervalMinutes = 1440).validate()
        assertThrows(IllegalArgumentException::class.java) {
            PollingConfig(intervalMinutes = 14).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PollingConfig(intervalMinutes = 1441).validate()
        }
    }

    @Test
    fun authAndProtocolErrorsBlockButNetworkRetriesAreBounded() {
        assertEquals("认证失败，请修复接收凭证", PollingPolicy.blockingReason(SyncOutcome.AUTH))
        assertEquals("服务协议不兼容，请检查地址或版本", PollingPolicy.blockingReason(SyncOutcome.PROTOCOL))
        assertEquals("", PollingPolicy.blockingReason(SyncOutcome.TRANSIENT))
        assertTrue(PollingPolicy.retry(SyncOutcome.TRANSIENT, 0))
        assertTrue(PollingPolicy.retry(SyncOutcome.STORAGE, 1))
        assertFalse(PollingPolicy.retry(SyncOutcome.TRANSIENT, 2))
        assertFalse(PollingPolicy.retry(SyncOutcome.AUTH, 0))
    }

    @Test
    fun wireRoundTripPreservesRevisionAndPause() {
        val original = PollingState(PollingConfig(true, 30, true, true), "", 3, 4, "ok", 7)
        assertEquals(original, PollingWire.decode(PollingWire.encode(original)))
        assertTrue(original.shouldSchedule)
        assertFalse(original.copy(config = original.config.copy(enabled = false)).shouldSchedule)
    }
}
