package io.github.beacon.app.ui.components

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class BrightTagPaletteTest {
    @Test fun allMetadataColorsStayBrightAndOpaque() {
        assertEquals(8, BeaconTagPalette.distinct().size)
        BeaconTagPalette.forEach {
            assertEquals(1f, it.alpha, 0f)
            assertTrue("Tag background must stay bright: $it", it.luminance() >= .42f)
        }
    }
    @Test fun smallMetadataTextHasStrongContrastAcrossAllHues() {
        BeaconTagPalette.forEach {
            val contrast = (it.luminance() + .05f) / (BeaconTagInk.luminance() + .05f)
            assertTrue("Small metadata text needs 7:1 contrast: $it / $contrast", contrast >= 7f)
        }
    }
    @Test fun selectionColorsRemainDistinctAndReadable() {
        assertNotEquals(BeaconChoiceSelected, BeaconChoiceUnselected)
        for (color in listOf(BeaconChoiceSelected, BeaconChoiceUnselected)) {
            assertTrue(color.luminance() >= .42f)
            assertTrue((color.luminance() + .05f) / (BeaconTagInk.luminance() + .05f) >= 7f)
        }
    }
}
