package io.github.beacon.app.domain.policy

import org.junit.Assert.*
import org.junit.Test

class CountdownDisplayPolicyTest {
    @Test fun roundsUpAndSchedulesTheNextVisibleChange() {
        assertEquals(2L, CountdownDisplayPolicy.minutes(61_000, 0))
        assertEquals(1_000L, CountdownDisplayPolicy.nextDelay(61_000, 0))
        assertEquals(1L, CountdownDisplayPolicy.minutes(61_000, 1_000))
        assertEquals(60_000L, CountdownDisplayPolicy.nextDelay(61_000, 1_000))
    }
    @Test fun stopsAtExpiryAndNeverDisplaysZeroMinutesBeforeExpiry() {
        assertEquals(1L, CountdownDisplayPolicy.minutes(1_000, 999))
        assertEquals(1L, CountdownDisplayPolicy.nextDelay(1_000, 999))
        assertEquals(0L, CountdownDisplayPolicy.minutes(1_000, 1_000))
        assertNull(CountdownDisplayPolicy.nextDelay(1_000, 1_000))
    }
}
