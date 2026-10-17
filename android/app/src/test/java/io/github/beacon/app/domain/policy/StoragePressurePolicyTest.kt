package io.github.beacon.app.domain.policy

import org.junit.Assert.*
import org.junit.Test

class StoragePressurePolicyTest {
    @Test
    fun reserveBoundaryAndUnreadableSpaceFailClosed() {
        assertFalse(StoragePressurePolicy.allowsNewMessage(-1))
        assertFalse(StoragePressurePolicy.allowsNewMessage(0))
        assertFalse(StoragePressurePolicy.allowsNewMessage(StoragePressurePolicy.RESERVE_BYTES - 1))
        assertTrue(StoragePressurePolicy.allowsNewMessage(StoragePressurePolicy.RESERVE_BYTES))
        assertTrue(StoragePressurePolicy.allowsNewMessage(Long.MAX_VALUE))
    }
}
