package io.github.beacon.app.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetConfigurationTest {
    @Test fun appearanceResetPreservesIdentityContentOrderAndExplicitBehavior() {
        val raw="""{"source":"source","identity":":quota","metrics":["b","a"],"template":"nested_rings","refresh":"economy","background":"dark","global_color":"#3478F6","number_scale":115,"list_count":4}"""
        val reset=WidgetConfiguration.resetAppearance(raw)
        assertEquals("source",reset.getString("source"))
        assertEquals("[\"b\",\"a\"]",reset.getJSONArray("metrics").toString())
        assertEquals("nested_rings",reset.getString("template"))
        assertEquals("economy",reset.getString("refresh"))
        assertEquals(4,reset.getInt("list_count"))
        assertFalse(reset.has("background"));assertFalse(reset.has("global_color"))
        assertTrue(WidgetConfiguration.equivalent(reset.toString(),
            org.json.JSONObject(reset.toString()).put("background","system").put("number_scale",100).toString(),"SQUARE"))
    }
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
