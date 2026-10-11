package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan
import org.junit.Assert.*
import org.junit.Test

class ReceiveModePolicyTest {
    @Test
    fun rulesUseEitherConditionAndPauseAlwaysWins() {
        val rules = ReceivePlan(ReceiveMode.RULES)
        assertTrue(ReceiveModePolicy.realtime(rules, true, true, true, false))
        assertTrue(ReceiveModePolicy.realtime(rules, true, true, false, true))
        assertFalse(ReceiveModePolicy.realtime(rules, true, true, false, false))
        assertFalse(ReceiveModePolicy.realtime(rules, false, true, true, true))
        assertFalse(ReceiveModePolicy.realtime(rules, true, false, true, true))
        assertFalse(ReceiveModePolicy.realtime(ReceivePlan(), true, true, true, true))
    }

    @Test
    fun lowBatteryOverridesScheduleAndNetworkConditions() {
        val plan =
            ReceivePlan(
                ReceiveMode.RULES,
                scheduleEnabled = true,
                scheduleStart = 540,
                scheduleEnd = 1080,
                lowBatteryEnabled = true,
                lowBatteryPercent = 20,
            )
        assertFalse(ReceiveModePolicy.realtime(plan, true, true, true, true, 20, 600))
        assertTrue(ReceiveModePolicy.realtime(plan, true, true, false, false, 21, 600))
        assertFalse(ReceiveModePolicy.realtime(plan, false, true, true, true, 100, 600))
    }

    @Test
    fun scheduledPowerSavingOverridesChargingAndWindowEndsExclusively() {
        val plan =
            ReceivePlan(
                ReceiveMode.RULES,
                scheduleEnabled = true,
                scheduleStart = 540,
                scheduleEnd = 1080,
                scheduleRealtime = false,
            )
        assertFalse(ReceiveModePolicy.realtime(plan, true, true, true, true, 100, 540))
        assertTrue(ReceiveModePolicy.realtime(plan, true, true, true, true, 100, 1080))
    }

    @Test
    fun overnightWindowAndValidation() {
        assertTrue(ReceiveModePolicy.inWindow(1380, 1320, 480))
        assertTrue(ReceiveModePolicy.inWindow(0, 1320, 480))
        assertFalse(ReceiveModePolicy.inWindow(480, 1320, 480))
        assertFalse(ReceiveModePolicy.inWindow(720, 1320, 480))
        assertThrows(IllegalArgumentException::class.java) {
            ReceivePlan(scheduleEnabled = true, scheduleStart = 10, scheduleEnd = 10).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReceivePlan(lowBatteryPercent = 101).validate()
        }
    }
}
