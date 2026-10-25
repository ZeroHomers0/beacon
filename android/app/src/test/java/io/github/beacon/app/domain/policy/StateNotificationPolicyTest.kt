package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StateNotificationPolicyTest {
    private val zone=ZoneId.of("UTC")
    @Test fun invalidSnapshotsCannotCreateNotifications() {
        for(status in listOf(DataStatus.MISSING,DataStatus.WAITING,DataStatus.EXPIRED,DataStatus.CLEARED))
            assertTrue(StateNotificationPolicy.shouldCancel(status,true,false,10,QuietHours(),0,zone))
        assertFalse(StateNotificationPolicy.shouldCancel(DataStatus.STALE,true,false,5,QuietHours(),0,zone))
    }
    @Test fun updatesAndQuietHoursNeverRealert() {
        assertFalse(StateNotificationPolicy.shouldAlert(false,10,QuietHours(),0,zone))
        val quiet=QuietHours(enabled=true,startMinute=0,endMinute=60)
        assertFalse(StateNotificationPolicy.shouldAlert(true,10,quiet,0,zone))
        assertTrue(StateNotificationPolicy.shouldAlert(true,5,QuietHours(),0,zone))
    }
}
