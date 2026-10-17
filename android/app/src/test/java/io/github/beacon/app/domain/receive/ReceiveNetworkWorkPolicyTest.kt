package io.github.beacon.app.domain.receive

import org.junit.Assert.*
import org.junit.Test

class ReceiveNetworkWorkPolicyTest {
    @Test
    fun liveConnectionSkipsOnlyAfterSuccessfulHttpAndForAtMostOneHour() {
        val policy = ReceiveNetworkWorkPolicy()
        assertFalse(policy.skipPeriodicSync(1, 100, true))
        policy.completed(1, 100)
        assertTrue(policy.skipPeriodicSync(1, 101, true))
        assertFalse(policy.skipPeriodicSync(1, 101, false))
        assertFalse(policy.skipPeriodicSync(2, 101, true))
        assertFalse(policy.skipPeriodicSync(1, 99, true))
        assertFalse(
            policy.skipPeriodicSync(1, 100 + ReceiveNetworkWorkPolicy.RECONCILE_INTERVAL_MS, true)
        )
    }

    @Test
    fun serverCooldownExpiresAndDoesNotCarryAcrossConfigurationChanges() {
        val policy = ReceiveNetworkWorkPolicy()
        policy.defer(1, 100, 60_000)
        assertTrue(policy.waiting(1, 60_099))
        assertFalse(policy.waiting(1, 60_100))
        assertFalse(policy.waiting(2, 101))
        policy.completed(1, 200)
        assertFalse(policy.waiting(1, 201))
    }
}
