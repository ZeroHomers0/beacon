package io.github.beacon.app.domain.receive

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeReconnectPolicyTest {
    @Test
    fun backsOffExponentiallyAndCaps() {
        assertEquals(1_000L, RealtimeReconnectPolicy.delayMs(1))
        assertEquals(2_000L, RealtimeReconnectPolicy.delayMs(2))
        assertEquals(4_000L, RealtimeReconnectPolicy.delayMs(3))
        assertEquals(300_000L, RealtimeReconnectPolicy.delayMs(20))
        assertEquals(300_000L, RealtimeReconnectPolicy.delayMs(100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroFailures() {
        RealtimeReconnectPolicy.delayMs(0)
    }
}
