package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.CleanupPolicy
import org.junit.Assert.*
import org.junit.Test

class CustomCleanupPolicyTest {
    @Test
    fun customRetentionAndSmallCapacityAreValid() {
        CleanupPolicy(14, 12, true).validate()
        CleanupPolicy(0, 1, false).validate()
        CleanupPolicy(3650, 100000, true).validate()
    }

    @Test
    fun negativeOverflowAndZeroCapacityAreRejected() {
        for (policy in
            listOf(
                CleanupPolicy(-1),
                CleanupPolicy(3651),
                CleanupPolicy(maxMessages = 0),
                CleanupPolicy(maxMessages = 100001),
            )) assertThrows(IllegalArgumentException::class.java) { policy.validate() }
    }
}
