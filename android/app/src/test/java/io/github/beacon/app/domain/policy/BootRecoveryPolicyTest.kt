package io.github.beacon.app.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class BootRecoveryPolicyTest {
    @Test
    fun availableCredentialsAllowRecovery() {
        assertEquals(BootRecoveryDecision.RESTORE, BootRecoveryPolicy.decide(userUnlocked = true))
    }

    @Test
    fun unavailableCredentialsWaitInsteadOfRecordingAPhantomRecovery() {
        assertEquals(
            BootRecoveryDecision.AWAITING_UNLOCK,
            BootRecoveryPolicy.decide(userUnlocked = false),
        )
    }
}
