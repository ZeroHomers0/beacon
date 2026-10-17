package io.github.beacon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolContractTest {
    @Test
    fun pageSizeNeverExceedsWhatTheServerAdvertises() {
        // Requesting past the server's bound is a 400 the client cannot recover from mid-run.
        assertEquals(25, ProtocolContract.pageSize(100))
        assertEquals(25, ProtocolContract.pageSize(25))
        assertEquals(10, ProtocolContract.pageSize(10))
        assertEquals(1, ProtocolContract.pageSize(1))
    }

    @Test
    fun absentOrNonsensicalAdvertisementsKeepTheClientDefault() {
        // An older server may omit the field, and any non-positive value is meaningless.
        assertEquals(HttpLimits.PAGE_SIZE, ProtocolContract.pageSize(null))
        assertEquals(HttpLimits.PAGE_SIZE, ProtocolContract.pageSize(0))
        assertEquals(HttpLimits.PAGE_SIZE, ProtocolContract.pageSize(-1))
    }

    @Test
    fun advertisedVersionsOutsideTheSupportedRangeAreRejected() {
        assertEquals(true, ProtocolContract.httpSupported(1))
        assertEquals(false, ProtocolContract.httpSupported(0))
        assertEquals(false, ProtocolContract.httpSupported(2))
        assertEquals(false, ProtocolContract.realtimeSupported(1))
        assertEquals(true, ProtocolContract.realtimeSupported(2))
    }
}
