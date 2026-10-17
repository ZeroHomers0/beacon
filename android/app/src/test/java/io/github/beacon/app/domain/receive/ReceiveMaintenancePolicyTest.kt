package io.github.beacon.app.domain.receive

import org.junit.Assert.*
import org.junit.Test

class ReceiveMaintenancePolicyTest {
    @Test
    fun burstDoesNotRunCleanupForEveryMessage() {
        val policy = ReceiveMaintenancePolicy()
        assertTrue(policy.due(0))
        policy.completed(0)
        repeat(99) { assertFalse(policy.due(1)) }
        assertTrue(policy.due(1))
        policy.completed(1)
        assertFalse(policy.due(899_999))
        assertTrue(policy.due(900_001))
    }

    @Test
    fun jitterStaysBoundedAndConfiguredHeartbeatIsAnUpperBound() {
        for (failures in 1..100) repeat(10) {
            val delay = RealtimeReconnectPolicy.jitteredDelayMs(failures)
            val maximum = RealtimeReconnectPolicy.delayMs(failures)
            assertTrue(delay in maximum * 3 / 4..maximum)
        }
        val heartbeat = io.github.beacon.app.domain.model.RealtimeHeartbeat
        assertEquals(60, heartbeat.DEFAULT_SECONDS)
        assertEquals(180, heartbeat.forFailures(180, 0))
        assertEquals(60, heartbeat.forFailures(180, 2))
        assertEquals(30, heartbeat.forFailures(30, 10))
    }
}
