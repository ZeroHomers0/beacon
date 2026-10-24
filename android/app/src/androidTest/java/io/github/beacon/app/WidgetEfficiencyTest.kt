package io.github.beacon.app

import android.appwidget.*
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetEfficiencyTest {
    @get:Rule val rule=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun row()=JSONObject().put("schema_version",1).put("application_id","").put("topic_key","efficiency").put("title","效率验收").put("body","正文")
        .put("revision",1).put("accepted_at_ms",1700000000000L).put("expires_at_ms",System.currentTimeMillis()+600000)
        .put("blocks",JSONArray("""[{"id":"v","kind":"progress","label":"进度","value":40,"max":100}]"""))

    @Test fun visualFingerprintIgnoresRevisionAndUnshownTimestamp(){
        val a=row();val config=JSONObject().put("template","single_ring").put("updated",false)
        val b=JSONObject(a.toString()).put("revision",999).put("accepted_at_ms",1800000000000L)
        assertEquals(WidgetPerformance.displayKey(a,config),WidgetPerformance.displayKey(b,config))
        b.getJSONArray("blocks").getJSONObject(0).put("value",41)
        assertNotEquals(WidgetPerformance.displayKey(a,config),WidgetPerformance.displayKey(b,config))
        val text=JSONObject().put("template","text").put("updated",false)
        assertEquals(WidgetPerformance.displayKey(a,text),WidgetPerformance.displayKey(b,text))
        b.put("body","另一段正文")
        assertNotEquals(WidgetPerformance.displayKey(a,text),WidgetPerformance.displayKey(b,text))
        assertNotEquals(WidgetPerformance.displayKey(a,config),WidgetPerformance.displayKey(JSONObject(a.toString()).put("stale_at_ms",1),config))
    }

    @Test fun observerStopsWhenEditorLeavesForegroundAndReloadsOnReturn(){
        val source="e".repeat(64)
        StateCache(context).use{it.apply(source,listOf(row()),"one",true)}
        try{
            ActivityScenario.launch<StateWidgetActivity>(Intent(context,StateWidgetActivity::class.java).putExtra("source",source).putExtra("identity",":efficiency")).use{scenario->
                rule.waitUntil(5000){WidgetPerformance.observers.get()>0}
                scenario.moveToState(Lifecycle.State.CREATED)
                rule.waitUntil(5000){WidgetPerformance.observers.get()==0L}
                StateCache(context).use{it.apply(source,listOf(row().put("revision",2).put("title","后台更新")),"two",false)}
                scenario.moveToState(Lifecycle.State.RESUMED)
                rule.waitUntil(5000){WidgetPerformance.observers.get()>0}
            }
            rule.waitUntil(5000){WidgetPerformance.observers.get()==0L}
        }finally{StateCache(context).use{it.forget(source)}}
    }

    @Test fun identicalInstancesShareRasterAndUnrelatedTopicsDoNotRender(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val manager=AppWidgetManager.getInstance(context);val host=AppWidgetHost(context,20421108)
        val ids=mutableListOf<Int>();val source="d".repeat(64)
        val data=row().put("title","缓存验收"+System.nanoTime())
        StateCache(context).use{it.apply(source,listOf(data),"one",true)}
        try{
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try{repeat(2){
                val id=host.allocateAppWidgetId();ids+=id
                val options=Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,180);putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,180)}
                assertTrue(manager.bindAppWidgetIdIfAllowed(id,WidgetFormat.SQUARE.component(context),options))
                StateCache(context).use{it.preference("widget:$id",JSONObject().put("source",source).put("identity",":efficiency").put("template","single_ring").put("updated",false).toString())}
            }}finally{instrumentation.uiAutomation.dropShellPermissionIdentity()}
            val before=WidgetPerformance.renders.get();val reuse=WidgetPerformance.reused.get()
            BeaconStateWidget.refresh(context,ids.toIntArray())
            assertTrue("at most one new raster for identical instances",WidgetPerformance.renders.get()-before<=1)
            assertTrue("second instance reuses raster",WidgetPerformance.reused.get()>reuse)
            val after=WidgetPerformance.renders.get()
            BeaconStateWidget.refresh(context,source="unrelated",identities=setOf(":efficiency"))
            assertEquals(after,WidgetPerformance.renders.get())
            val skipped=WidgetPerformance.skipped.get()
            BeaconStateWidget.refresh(context,source=source,identities=setOf(":efficiency"))
            assertTrue(WidgetPerformance.skipped.get()>=skipped+2)
        }finally{host.deleteHost();StateCache(context).use{cache->ids.forEach{cache.preference("widget:$it","")};cache.forget(source)}}
    }

    @Test fun listControlsAndPreviewRemainUsableInBothStyles(){
        val source="c".repeat(64)
        val r=row().put("blocks",JSONArray().put(JSONObject().put("kind","list").put("id","news").put("label","热搜榜").put("items",JSONArray((1..12).map{JSONObject().put("id","n$it").put("text","第$it 条新闻：用于验证两行展示与榜单分段的长标题")}))))
        val styles=io.github.beacon.app.data.local.UiStyleStore(context);val previous=styles.load()
        StateCache(context).use{it.apply(source,listOf(r),"list",true)}
        try{for(style in io.github.beacon.app.domain.model.BeaconUiStyle.entries){
            styles.save(style)
            ActivityScenario.launch<StateWidgetActivity>(Intent(context,StateWidgetActivity::class.java).putExtra("source",source).putExtra("identity",":efficiency").putExtra("format","WIDE")).use{
                rule.waitUntil(5000){rule.onAllNodesWithText("小组件设置").fetchSemanticsNodes().isNotEmpty()}
                rule.onNodeWithTag("settings-list").performScrollToNode(hasText("起始排名"))
                rule.onNodeWithText("起始排名").performClick();rule.onNodeWithText("第7条",substring=false).performClick()
                rule.onNodeWithText("每条行数").performClick();rule.onNodeWithText("两行",substring=false).performClick()
                val folder=requireNotNull(context.getExternalFilesDir("state-qa"));folder.mkdirs()
                java.io.File(folder,"widget_list_settings_${style.name}.png").outputStream().use{stream->rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)}
                rule.onNodeWithContentDescription("查看预览").performClick()
                rule.waitUntil(5000){rule.onAllNodesWithContentDescription("组件预览").fetchSemanticsNodes().isNotEmpty()}
                java.io.File(folder,"widget_list_preview_${style.name}.png").outputStream().use{stream->rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)}
            }
        }}finally{styles.save(previous);StateCache(context).use{it.forget(source)}}
    }
    @Test fun colorMenuShowsSwatchesAndChangesOldDataPreview(){
        val source="a".repeat(64);val styles=io.github.beacon.app.data.local.UiStyleStore(context);val previous=styles.load()
        StateCache(context).use{it.apply(source,listOf(row().put("stale_at_ms",1)),"color",true)}
        try{for(style in io.github.beacon.app.domain.model.BeaconUiStyle.entries){
            styles.save(style)
            ActivityScenario.launch<StateWidgetActivity>(Intent(context,StateWidgetActivity::class.java).putExtra("source",source).putExtra("identity",":efficiency")).use{
                rule.waitUntil(5000){rule.onAllNodesWithContentDescription("组件预览").fetchSemanticsNodes().isNotEmpty()}
                val before=rule.onNodeWithContentDescription("组件预览").captureToImage().asAndroidBitmap()
                rule.onNodeWithTag("settings-list").performScrollToNode(hasText("所有内容的颜色"))
                rule.onNodeWithText("所有内容的颜色").performClick()
                rule.onNodeWithContentDescription("红色色样").assertExists()
                rule.onNodeWithText("红色",substring=false).performClick()
                rule.onNodeWithContentDescription("查看预览").performClick()
                rule.waitUntil(5000){rule.onAllNodesWithContentDescription("组件预览").fetchSemanticsNodes().isNotEmpty()}
                val after=rule.onNodeWithContentDescription("组件预览").captureToImage().asAndroidBitmap()
                assertFalse("old state must respond to a manual color change",before.sameAs(after))
                val folder=requireNotNull(context.getExternalFilesDir("state-qa"));folder.mkdirs()
                java.io.File(folder,"widget_color_${style.name}.png").outputStream().use{stream->rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)}
            }
        }}finally{styles.save(previous);StateCache(context).use{it.forget(source)}}
    }
    @Test fun explicitColorOverridesStalenessAndBands(){
        val data=row().put("stale_at_ms",1)
        data.getJSONArray("blocks").getJSONObject(0).put("bands",JSONArray("""[{"until":100,"tone":"normal"}]"""))
        for(template in listOf("numbers","single_ring","horizontal_bars")){
            val config=JSONObject().put("template",template).put("global_color","#E65B65").put("bands",true)
            val image=WidgetRenderer.render(data,config,180,180,false)
            val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
            assertTrue("$template keeps explicit red even for old data",pixels.count{it==0xFFE65B65.toInt()}>20)
        }
    }

    @Test fun extremeAllowedTuningPreservesOuterContentInsets(){
        val folder=requireNotNull(context.getExternalFilesDir("widget-qa"));folder.mkdirs()
        for(template in listOf("numbers","single_ring","nested_rings","ring_grid","horizontal_bars","vertical_bars"))for(format in WidgetFormat.entries){
            val count=when(template){"single_ring"->1;"nested_rings"->3;else->4}
            val data=row().put("blocks",JSONArray((1..count).map{JSONObject().put("kind","progress").put("id","v$it").put("label","很长的说明文字必须留足边距").put("value",65).put("max",100).put("unit","GB")}))
            val config=JSONObject().put("template",template).put("format",format.name).put("number_scale",115).put("label_scale",110).put("stroke_scale",115).put("global_color","#E65B65")
            assertNull(WidgetTuning.error(config))
            val bitmap=WidgetRenderer.render(data,config,format.width,format.height,false)
            val inset=kotlin.math.max(12f,kotlin.math.min(format.width,format.height)*40f/440f).toInt()-1
            for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){
                if(bitmap.getPixel(x,y)==0xFFE65B65.toInt())assertTrue("$template $format cannot draw outside safe insets",x>=inset&&x<bitmap.width-inset&&y>=inset&&y<bitmap.height-inset)
            }
            java.io.File(folder,"tuned_${template}_${format.name}.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
        assertNotNull(WidgetTuning.error(JSONObject().put("number_scale",999)))
        assertEquals(1f,WidgetTuning.factor(JSONObject().put("stroke_scale",999),"stroke_scale"))
    }
    @Test fun recommendationsAndLargeLayoutsSupportMixedContent(){
        val r=row();r.getJSONArray("blocks").put(JSONObject().put("kind","list").put("id","news").put("items",JSONArray().put(JSONObject().put("id","1").put("text","新闻"))))
        val config=JSONObject().put("template",WidgetConfigRules.recommend(r))
        assertEquals(listOf("v"),WidgetConfigRules.defaults(r,config).map{it.optString("id")})
        val values=JSONArray((1..8).map{JSONObject().put("id","n$it").put("kind","metric").put("label","项目$it").put("value",it)})
        r.put("blocks",values);config.put("template","numbers").put("format","LARGE")
        assertNull(WidgetConfigRules.layoutError(r,config,360,360))
        assertNotNull(WidgetConfigRules.layoutError(r,config.put("format","SQUARE"),180,180))
    }
}
