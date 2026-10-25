package io.github.beacon.app.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetLayoutRulesTest {
    @Test fun nestedRingsAndLegendsOccupySeparateSafeRegions() {
        for((w,h) in listOf(180 to 180,270 to 180,360 to 180,360 to 360))for(count in 2..3) {
            val pad=WidgetGeometry.inset(w,h)
            val top=pad+24*WidgetGeometry.scale(w,h);val bottom=h-pad
            val plan=WidgetGeometry.nested(w,top,bottom,pad,count,1.1f,1.1f)
            assertTrue(plan.centerX-plan.radius-plan.stroke/2>=pad)
            assertTrue(plan.centerY-plan.radius-plan.stroke/2>=top)
            assertTrue(plan.centerY+plan.radius+plan.stroke/2<=bottom)
            if(plan.sideLegend)assertTrue(plan.centerX+plan.radius+plan.stroke/2<plan.legendX)
            else assertTrue(plan.centerY+plan.radius+plan.stroke/2<plan.legendY)
            assertTrue(plan.legendX+plan.legendWidth<=w-pad+.01f)
            assertTrue(plan.legendY+plan.legendHeight<=bottom+.01f)
            assertTrue(plan.radius-(count-1)*plan.stroke*1.65f-plan.stroke/2>0)
        }
        assertFalse(WidgetGeometry.nested(180,30f,165f,14f,3).sideLegend)
        assertTrue(WidgetGeometry.nested(270,30f,165f,14f,3).sideLegend)
        assertFalse(WidgetGeometry.nested(360,45f,335f,20f,3).sideLegend)
    }
    @Test fun countdownChangesOnlyAtItsMinuteBoundary() {
        val now=1_000_000L;val target=now+125_000
        val row=JSONObject().put("blocks",JSONArray().put(JSONObject().put("kind","countdown").put("id","reset").put("target_at_ms",target)))
        val config=JSONObject().put("template","countdown").put("updated",false)
        assertEquals("3分钟",WidgetCountdown.caption(target,now))
        assertEquals(now+5000,WidgetCountdown.next(row,config,now))
        assertNull(WidgetCountdown.next(row,config,now,interactive=false))
        assertEquals(WidgetPerformance.displayKey(row,config,now),WidgetPerformance.displayKey(row,config,now+4000))
        assertNotEquals(WidgetPerformance.displayKey(row,config,now),WidgetPerformance.displayKey(row,config,now+5000))
        assertEquals("已到时间",WidgetCountdown.caption(target,target))
        assertNull(WidgetCountdown.next(row,config,target))
        assertNull(WidgetCountdown.next(row.put("expires_at_ms",now),config,now))
        row.remove("expires_at_ms");row.put("stale_at_ms",now)
        assertNull(WidgetCountdown.next(row,config,now))
        assertEquals(WidgetPerformance.displayKey(row,config,now),WidgetPerformance.displayKey(row,config,now+60_000))
    }
    @Test fun shortNamesAndTapBehaviorAreLocalAndBounded() {
        val config=JSONObject().put("source","source").put("identity",":quota").put("tap_action","detail")
            .put("short_labels",JSONObject().put("reset","7d"))
        assertNull(WidgetTuning.error(config))
        assertEquals("7d",WidgetConfiguration.resetAppearance(config.toString()).getJSONObject("short_labels").getString("reset"))
        config.getJSONObject("short_labels").put("reset","123456789")
        assertNotNull(WidgetTuning.error(config))
    }
}
