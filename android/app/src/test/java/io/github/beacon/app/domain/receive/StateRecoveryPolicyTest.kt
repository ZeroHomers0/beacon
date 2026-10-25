package io.github.beacon.app.domain.receive

import org.junit.Assert.*
import org.junit.Test

class StateRecoveryPolicyTest {
    @Test fun idleConnectionChecksAtFiveTenThenFifteenMinutes() {
        val policy=StateRecoveryPolicy();policy.reset(1000)
        var now=1000L
        for(interval in listOf(300_000L,600_000L,900_000L,900_000L)) {
            assertFalse(policy.due(now+interval-1))
            now+=interval
            assertTrue(policy.due(now));policy.attempted(now)
        }
    }
    @Test fun signalsPostponeRedundantSafetyCheckAndReconnectRestoresShortDeadline() {
        val policy=StateRecoveryPolicy();policy.reset(0)
        policy.attempted(100_000)
        assertFalse(policy.due(300_000));assertEquals(400_000L,policy.remaining(300_000))
        policy.attempted(400_000);policy.reset(500_000)
        assertEquals(300_000L,policy.remaining(500_000));assertTrue(policy.due(800_000))
        assertEquals(1L,policy.remaining(900_000))
    }
}
