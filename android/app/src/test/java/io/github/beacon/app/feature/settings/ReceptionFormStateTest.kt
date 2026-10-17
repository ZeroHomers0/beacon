package io.github.beacon.app.feature.settings

import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.ReceiveMode
import io.github.beacon.app.domain.model.ReceivePlan
import org.junit.Assert.*
import org.junit.Test

class ReceptionFormStateTest {
    @Test
    fun draftCanBeDiscarded() {
        val saved = PollingConfig(enabled = true, intervalMinutes = 30, charging = true)
        val savedPlan = ReceivePlan(ReceiveMode.RULES)
        val form = ReceptionFormState()
        form.reset(saved, savedPlan)
        form.interval = 60
        form.unmetered = true
        form.plan = savedPlan.copy(lowBatteryEnabled = true)
        form.edited = true
        assertTrue(form.differsFrom(saved, savedPlan))
        val updated = form.applyTo(saved)
        // Automatic reception is no longer optional, so saving always re-enables polling.
        assertTrue(updated.enabled)
        assertTrue(updated.charging)
        assertEquals(60, updated.intervalMinutes)
        assertTrue(updated.unmetered)
        assertTrue(form.pendingPlan().lowBatteryEnabled)
        // A failed save must leave the draft intact until the runtime acknowledges it.
        assertTrue(form.edited)
        form.reset(saved, savedPlan)
        assertFalse(form.edited)
        assertFalse(form.differsFrom(saved, savedPlan))
        assertEquals(saved.intervalMinutes, form.interval)
        assertEquals(savedPlan, form.plan)
    }

    @Test
    fun scheduleTextFeedsThePlan() {
        val config = PollingConfig()
        val savedPlan =
            ReceivePlan(scheduleEnabled = true, scheduleStart = 9 * 60, scheduleEnd = 18 * 60)
        val form = ReceptionFormState()
        form.reset(config, savedPlan)
        assertEquals("09:00", form.scheduleStartText)
        assertEquals("18:00", form.scheduleEndText)
        assertTrue(form.scheduleValid)
        form.scheduleEndText = "08:00"
        form.edited = true
        assertEquals(8 * 60, form.scheduleEnd)
        assertEquals(8 * 60, form.pendingPlan().scheduleEnd)
        assertTrue(form.differsFrom(config, savedPlan))
        // An unparsable time blocks the save instead of silently keeping the old value.
        form.scheduleStartText = "9:0"
        assertNull(form.scheduleStart)
        assertFalse(form.scheduleValid)
        // Identical endpoints are rejected too.
        form.scheduleStartText = "08:00"
        assertFalse(form.scheduleValid)
    }
}
