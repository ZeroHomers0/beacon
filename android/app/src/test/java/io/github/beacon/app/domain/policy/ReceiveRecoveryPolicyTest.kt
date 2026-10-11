package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ReceiveRecoveryPolicyTest {
    private val enabled = PollingState(config = PollingConfig(enabled = true))

    @Test
    fun pausedBlockedAndPowerSavingNeverStartRealtime() {
        val forbidden: () -> Unit = { error("Recovery must not enable realtime") }
        assertEquals(
            RecoveryStatus.DISABLED,
            ReceiveRecoveryPolicy.restore(
                ReceivePlan(ReceiveMode.REALTIME),
                PollingState(),
                forbidden,
            ),
        )
        assertEquals(
            RecoveryStatus.BLOCKED,
            ReceiveRecoveryPolicy.restore(
                ReceivePlan(ReceiveMode.REALTIME),
                enabled.copy(blocked = "binding required"),
                forbidden,
            ),
        )
        assertEquals(
            RecoveryStatus.POWER_SAVING,
            ReceiveRecoveryPolicy.restore(ReceivePlan(), enabled, forbidden),
        )
    }

    @Test
    fun bothRealtimePlansRequestStartWithoutClaimingConnectionSucceeded() {
        for (mode in listOf(ReceiveMode.REALTIME, ReceiveMode.RULES)) {
            var starts = 0
            assertEquals(
                RecoveryStatus.START_REQUESTED,
                ReceiveRecoveryPolicy.restore(ReceivePlan(mode), enabled) { starts++ },
            )
            assertEquals(1, starts)
        }
    }

    @Test
    fun systemStartRestrictionIsRecordedAndNotThrownFromBroadcast() {
        assertEquals(
            RecoveryStatus.START_RESTRICTED,
            ReceiveRecoveryPolicy.restore(ReceivePlan(ReceiveMode.REALTIME), enabled) {
                throw IllegalStateException("private system reason")
            },
        )
    }
}
