package io.github.beacon.app.domain.receive

import org.junit.Assert.*
import org.junit.Test

class AdaptiveHeartbeatPolicyTest {
    @Test
    fun rememberedNetworkIsProbedOnlyAfterThreeShortSuccesses() {
        val policy = AdaptiveHeartbeatPolicy(300)
        repeat(2) {
            assertNull(policy.acknowledged())
            assertEquals(60, policy.seconds)
        }
        assertEquals(60, policy.acknowledged())
        assertEquals(300, policy.seconds)
        assertEquals(60, policy.timedOut())
    }

    @Test
    fun stableNetworkGrowsAndFailureRecoversWithoutPermanentShortHeartbeat() {
        val policy = AdaptiveHeartbeatPolicy()
        repeat(12) { policy.acknowledged() }
        assertTrue(policy.seconds > 60)
        val reduced = policy.timedOut()
        assertTrue(reduced >= 60)
        repeat(9) { policy.acknowledged() }
        assertEquals(reduced, policy.seconds)
        repeat(3) { policy.acknowledged() }
        assertTrue(policy.seconds > reduced)
        repeat(100) { policy.acknowledged() }
        assertEquals(300, policy.seconds)
    }

    @Test
    fun growthIsGeometricSoTheShortestIntervalIsLeftQuickly() {
        // Three acknowledgements are one completed probe. The first probe only confirms the
        // starting interval; after that each probe doubles. Stepping by a fixed 30 seconds
        // instead kept a fresh install at 60 seconds — the most expensive interval this app
        // uses — for about an hour.
        val policy = AdaptiveHeartbeatPolicy()
        assertEquals(60, policy.seconds)
        repeat(3) { policy.acknowledged() }
        assertEquals(60, policy.seconds)
        repeat(3) { policy.acknowledged() }
        assertEquals(120, policy.seconds)
        repeat(3) { policy.acknowledged() }
        assertEquals(240, policy.seconds)
        repeat(3) { policy.acknowledged() }
        assertEquals(300, policy.seconds)
    }
}
