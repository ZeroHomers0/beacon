package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.PollingConfig
import org.junit.Assert.*
import org.junit.Test

class PollingIntervalTest {
    @Test
    fun productPresetsAreValidSystemIntervals() {
        listOf(15, 30, 60, 120).forEach { PollingConfig(intervalMinutes = it).validate() }
    }

    @Test
    fun customRangeStillAllowsLongLowPowerSchedules() {
        PollingConfig(intervalMinutes = 1440).validate()
        assertThrows(IllegalArgumentException::class.java) {
            PollingConfig(intervalMinutes = 10).validate()
        }
    }
}
