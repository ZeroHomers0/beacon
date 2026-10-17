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
    fun growthVisitsEverySupportedStep() {
        val policy = AdaptiveHeartbeatPolicy()
        for (expected in listOf(60, 90, 120, 180, 240, 300, 300)) {
            repeat(3) { policy.acknowledged() }
            assertEquals(expected, policy.seconds)
        }
    }

    @Test
    fun failedIntervalIsNotRetriedDuringCooldown() {
        var now = 1_000L
        val policy = AdaptiveHeartbeatPolicy(nowMs = { now })
        repeat(9) { policy.acknowledged() }
        assertEquals(120, policy.seconds)
        assertEquals(90, policy.timedOut())
        assertEquals(120, policy.failedSeconds)
        repeat(100) { policy.acknowledged() }
        assertEquals(90, policy.seconds)
        now += AdaptiveHeartbeatPolicy.FAILURE_COOLDOWN_MS
        repeat(3) { policy.acknowledged() }
        assertEquals(120, policy.seconds)
    }

    @Test
    fun rememberedFailureSurvivesANewSession() {
        val now = 1_000L
        val policy = AdaptiveHeartbeatPolicy(
            rememberedSeconds = 90,
            failedSeconds = 120,
            retryAfterMs = now + AdaptiveHeartbeatPolicy.FAILURE_COOLDOWN_MS,
            nowMs = { now },
        )
        repeat(100) { policy.acknowledged() }
        assertEquals(90, policy.seconds)
    }

    @Test
    fun oldHintsAreRoundedDownAndInvalidCooldownIsIgnored() {
        val policy = AdaptiveHeartbeatPolicy(
            rememberedSeconds = 270,
            failedSeconds = 300,
            retryAfterMs = Long.MAX_VALUE,
            nowMs = { 1_000L },
        )
        repeat(3) { policy.acknowledged() }
        assertEquals(240, policy.seconds)
        repeat(3) { policy.acknowledged() }
        assertEquals(300, policy.seconds)
    }
}
