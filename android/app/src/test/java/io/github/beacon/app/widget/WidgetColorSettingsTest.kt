package io.github.beacon.app.widget

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetColorSettingsTest {
    @Test fun switchingDefaultsPreservesIndividualColorsAndUnrelatedSettings() {
        val original = JSONObject("""{"color":"semantic","global_color":"#29B86A","content_colors":{"quota":"#E65B65"},"bands":true,"background":"dark"}""")
        for (mode in 0..2) {
            val next = WidgetColors.withDefaultMode(original, mode)
            assertEquals(mode, WidgetColors.defaultMode(next))
            assertEquals("#E65B65", next.getJSONObject("content_colors").getString("quota"))
            assertTrue(next.getBoolean("bands"))
            assertEquals("dark", next.getString("background"))
        }
        assertEquals("#29B86A", original.getString("global_color"))
    }

    @Test fun choosingSpecifiedColorFromAutomaticProvidesAnActualColor() {
        val next = WidgetColors.withDefaultMode(JSONObject(), 2)
        assertEquals(2, WidgetColors.defaultMode(next))
        assertTrue(next.getString("global_color").isNotBlank())
    }

    @Test fun legacyExplicitColorTakesPrecedenceOverSemanticMode() {
        assertEquals(2, WidgetColors.defaultMode(JSONObject("""{"color":"semantic","global_color":"#3478F6"}""")))
        assertEquals(1, WidgetColors.defaultMode(JSONObject("""{"color":"semantic"}""")))
        assertEquals(0, WidgetColors.defaultMode(JSONObject()))
    }
}
