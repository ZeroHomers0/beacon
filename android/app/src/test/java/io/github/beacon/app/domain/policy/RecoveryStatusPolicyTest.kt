package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.RecoverySnapshot
import io.github.beacon.app.domain.model.RecoveryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryStatusPolicyTest {
    @Test
    fun aParkedLockedBootIsReportedOnlyWhileNoRealRecordExists() {
        assertEquals(
            RecoveryStatus.AWAITING_UNLOCK,
            RecoveryStatusPolicy.merge(RecoverySnapshot(), lockedBootPending = true).status,
        )
    }

    @Test
    fun noParkedNoteLeavesTheStoredSnapshotUntouched() {
        val stored = RecoverySnapshot(RecoveryStatus.START_REQUESTED, 12_345L, 2)
        assertEquals(stored, RecoveryStatusPolicy.merge(stored, lockedBootPending = false))
    }

    @Test
    fun aRealRecordSupersedesTheParkedNote() {
        val stored = RecoverySnapshot(RecoveryStatus.DISABLED, 999L, 0)
        assertEquals(stored, RecoveryStatusPolicy.merge(stored, lockedBootPending = true))
    }

    @Test
    fun theMergedSnapshotKeepsTheAttemptCount() {
        val stored = RecoverySnapshot(RecoveryStatus.NONE, 0L, 4)
        assertEquals(4, RecoveryStatusPolicy.merge(stored, lockedBootPending = true).attempts)
    }

    @Test
    fun aLockedReadIsNotMistakenForASettingsFailure() {
        val unreadable = RecoverySnapshot(RecoveryStatus.SETTINGS_ERROR, 0L, 0)
        assertEquals(
            RecoveryStatus.AWAITING_UNLOCK,
            RecoveryStatusPolicy.merge(unreadable, lockedBootPending = true).status,
        )
    }
}
