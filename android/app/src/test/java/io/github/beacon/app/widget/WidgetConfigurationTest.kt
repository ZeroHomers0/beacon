package io.github.beacon.app.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetConfigurationTest {
    @Test fun restoringAnOmittedDefaultDoesNotRemainDirty() {
        assertTrue(WidgetConfiguration.equivalent("{}", """{"updated":true,"global_color":"","content_colors":{},"decimals":-1}""", "SQUARE"))
        assertFalse(WidgetConfiguration.equivalent("{}", """{"updated":false}""", "SQUARE"))
    }
    @Test fun keyOrderAndEmptyAutomaticColorsDoNotMatter() {
        assertTrue(WidgetConfiguration.equivalent("""{"title":"标题","color":"fixed"}""", """{"content_colors":{"quota":""},"color":"fixed","title":"标题"}""", "WIDE"))
    }
    @Test fun selectedContentOrderAndExplicitColorsStillMatter() {
        assertFalse(WidgetConfiguration.equivalent("""{"metrics":["a","b"]}""", """{"metrics":["b","a"]}""", "SQUARE"))
        assertFalse(WidgetConfiguration.equivalent("{}", """{"global_color":"#3478F6"}""", "SQUARE"))
    }
}
