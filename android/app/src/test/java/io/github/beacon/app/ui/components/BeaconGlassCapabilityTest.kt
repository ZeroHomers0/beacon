package io.github.beacon.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class BeaconGlassCapabilityTest {
    @Test fun unsupportedDevicesKeepOrdinaryMiuix() {
        assertFalse(supportsBeaconGlass(32, true, false, true))
        assertFalse(supportsBeaconGlass(33, false, false, true))
        assertFalse(supportsBeaconGlass(33, true, true, true))
        assertFalse(supportsBeaconGlass(33, true, false, false))
        assertTrue(supportsBeaconGlass(33, true, false, true))
        assertTrue(supportsBeaconGlass(37, true, false, true))
    }
}
