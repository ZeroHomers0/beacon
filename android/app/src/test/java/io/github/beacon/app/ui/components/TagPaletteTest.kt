package io.github.beacon.app.ui.components

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class TagPaletteTest {
    private fun contrast(colors: BeaconTagColors): Float {
        val values = listOf(colors.container.luminance(), colors.content.luminance()).sorted()
        return (values[1] + .05f) / (values[0] + .05f)
    }
    @Test fun everySemanticLabelMeetsSmallTextContrastInBothThemes() {
        for (dark in listOf(false, true)) for (tone in BeaconTagTone.entries) {
            assertTrue("$dark / $tone", contrast(tagColors(dark, tone)) >= 4.5f)
            assertEquals(1f, tagColors(dark, tone).container.alpha, 0f)
        }
    }
    @Test fun colourIsRestrictedToTextWhileCategorySurfacesRemainNeutral() {
        for (dark in listOf(false, true)) {
            assertEquals(1, BeaconTagTone.entries.map { tagColors(dark, it).container }.distinct().size)
            assertEquals(4, BeaconTagTone.entries.map { tagColors(dark, it).content }.distinct().size)
        }
        assertNotEquals(tagColors(false).container, tagColors(true).container)
    }
    @Test fun selectedChoicesAreDistinctAndReadableInBothThemes() {
        for (dark in listOf(false, true)) {
            assertNotEquals(miuixChoiceColors(dark, true).container, miuixChoiceColors(dark, false).container)
            for (selected in listOf(false, true)) assertTrue(contrast(miuixChoiceColors(dark, selected)) >= 4.5f)
        }
    }
}
