package io.github.beacon.app.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetUpdateTimeTest {
    private fun row(at:Long)=JSONObject().put("accepted_at_ms",at).put("blocks",JSONArray().put(JSONObject().put("kind","metric").put("id","quota").put("value",42).put("label","额度")))
    private fun config(updated:Boolean)=JSONObject().put("template","numbers").put("updated",updated)

    @Test fun unchangedValuesReuseImageWithinFiveMinutes() {
        assertEquals(300_000L,WidgetPerformance.updatedAt(row(599_999)))
        assertEquals(WidgetPerformance.displayKey(row(300_000),config(true)),WidgetPerformance.displayKey(row(599_999),config(true)))
        assertNotEquals(WidgetPerformance.displayKey(row(599_999),config(true)),WidgetPerformance.displayKey(row(600_000),config(true)))
    }
    @Test fun hidingUpdateTimeAvoidsTimestampOnlyRedraws() {
        assertEquals(WidgetPerformance.displayKey(row(300_000),config(false)),WidgetPerformance.displayKey(row(900_000),config(false)))
        val changed=row(900_000).apply{getJSONArray("blocks").getJSONObject(0).put("value",41)}
        assertNotEquals(WidgetPerformance.displayKey(row(300_000),config(false)),WidgetPerformance.displayKey(changed,config(false)))
    }
    @Test fun staleAndExpiryTransitionsStillInvalidateImages() {
        val value=row(300_000).put("stale_at_ms",500_000).put("expires_at_ms",700_000)
        assertNotEquals(WidgetPerformance.displayKey(value,config(false),499_999),WidgetPerformance.displayKey(value,config(false),500_000))
        assertNotEquals(WidgetPerformance.displayKey(value,config(false),699_999),WidgetPerformance.displayKey(value,config(false),700_000))
    }
}
