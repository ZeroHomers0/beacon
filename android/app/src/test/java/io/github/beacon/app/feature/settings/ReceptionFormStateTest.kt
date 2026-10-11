package io.github.beacon.app.feature.settings

import io.github.beacon.app.domain.model.PollingConfig
import org.junit.Assert.*
import org.junit.Test

class ReceptionFormStateTest {
    @Test
    fun draftPreservesMasterSwitchAndCanBeDiscarded() {
        val saved = PollingConfig(enabled = true, intervalMinutes = 30, charging = true)
        val form = ReceptionFormState()
        form.reset(saved)
        form.interval = 60
        form.unmetered = true
        form.edited = true
        assertTrue(form.differsFrom(saved))
        val updated = form.applyTo(saved)
        assertTrue(updated.enabled)
        assertTrue(updated.charging)
        assertEquals(60, updated.intervalMinutes)
        assertTrue(updated.unmetered)
        // A failed save must leave the draft intact until the runtime acknowledges it.
        assertTrue(form.edited)
        form.reset(saved)
        assertFalse(form.edited)
        assertFalse(form.differsFrom(saved))
    }
}
