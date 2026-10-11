package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class StorageReadinessTest {
    private fun preview(total: Long, protected: Long = 0, planned: Int = 0) =
        CleanupPreview("opaque", total, 0, 0, 4096, planned.toLong(), protected, planned)

    @Test
    fun lowSpaceBoundaryDoesNotTreatMissingSampleAsFullDisk() {
        assertEquals(StoragePressure.UNKNOWN, StorageHealth.pressure(-1))
        assertEquals(StoragePressure.LOW, StorageHealth.pressure(0))
        assertEquals(StoragePressure.LOW, StorageHealth.pressure(StorageHealth.LOW_SPACE_BYTES))
        assertEquals(
            StoragePressure.NORMAL,
            StorageHealth.pressure(StorageHealth.LOW_SPACE_BYTES + 1),
        )
    }

    @Test
    fun zeroCleanupDistinguishesRulesFromProtectedMessages() {
        assertEquals(CleanupExplanation.EMPTY, StorageHealth.explanation(preview(0)))
        assertEquals(CleanupExplanation.WITHIN_RULES, StorageHealth.explanation(preview(4)))
        assertEquals(CleanupExplanation.PROTECTED, StorageHealth.explanation(preview(4, 4)))
        assertEquals(CleanupExplanation.READY, StorageHealth.explanation(preview(4, 3, 1)))
    }

    @Test
    fun previousSyncSuccessCannotProveUnconfiguredOrFailedServerWorks() {
        assertFalse(SetupProgress.evaluate(false, true, true, SyncOutcome.OK).sync)
        assertFalse(SetupProgress.evaluate(true, false, true, SyncOutcome.OK).sync)
        assertFalse(SetupProgress.evaluate(true, null, true, SyncOutcome.OK).connection)
        val ready = SetupProgress.evaluate(true, true, false, SyncOutcome.OK)
        assertTrue(ready.connection)
        assertTrue(ready.sync)
        assertFalse(ready.notifications)
        assertFalse(SetupProgress.evaluate(true, true, true, SyncOutcome.AUTH).sync)
    }
}
