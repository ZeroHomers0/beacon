package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import org.junit.Assert.*
import org.junit.Test

class PollingStatusTest {
    @Test
    fun enabledDoesNotMeanRecentlySuccessful() {
        val state = PollingState(PollingConfig(true), lastResult = "尚未自动同步")
        assertTrue(state.shouldSchedule)
        assertEquals(0, state.lastSuccessMs)
    }

    @Test
    fun blockedReasonIsVisibleAndStopsScheduling() {
        val state = PollingState(PollingConfig(true), blocked = "认证失败，请修复接收凭证")
        assertFalse(state.shouldSchedule)
        assertTrue(state.blocked.contains("认证"))
    }
}
