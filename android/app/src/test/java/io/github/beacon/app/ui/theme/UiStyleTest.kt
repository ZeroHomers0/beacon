package io.github.beacon.app.ui.theme

import io.github.beacon.app.domain.model.BeaconUiStyle
import org.junit.Assert.*
import org.junit.Test

class UiStyleTest {
    @Test
    fun stylesAreExplicitAndStable() {
        assertEquals(BeaconUiStyle.MATERIAL3, BeaconUiStyle.valueOf("MATERIAL3"))
        assertEquals(BeaconUiStyle.MIUIX, BeaconUiStyle.valueOf("MIUIX"))
        assertNotEquals(BeaconUiStyle.MATERIAL3, BeaconUiStyle.MIUIX)
    }
}
