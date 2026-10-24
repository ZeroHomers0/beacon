package io.github.beacon.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.SizeF
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.theme.BeaconTheme
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetManagementTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()

    @Test fun managerListsBothFormatsAndOpensEachRealInstance(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val manager=AppWidgetManager.getInstance(context);val host=AppWidgetHost(context,20421101)
        val ids=mutableListOf<Int>();val source="manager-test"
        val row=JSONObject().put("schema_version",1).put("topic_key","manager:test").put("application_id","").put("revision",1).put("title","管理测试额度").put("body","短周期24%，长周期62%")
            .put("application_name","额度监控").put("expires_at_ms",System.currentTimeMillis()+600000).put("blocks",JSONArray("""[{"kind":"progress","id":"short","label":"短周期","value":24,"max":100,"unit":"%"}]"""))
        StateCache(context).use{it.apply(source,listOf(row),"manager",true)}
        try{
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try{for(format in WidgetFormat.entries){
                val id=host.allocateAppWidgetId();ids+=id
                val options=Bundle().apply{
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,format.width);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,format.width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,format.height);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,format.height)
                    if(android.os.Build.VERSION.SDK_INT>=31)putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,arrayListOf(SizeF(format.width.toFloat(),format.height.toFloat())))
                }
                assertTrue(manager.bindAppWidgetIdIfAllowed(id,format.component(context),options))
                StateCache(context).use{it.preference("widget:$id",JSONObject().put("source",source).put("identity",":manager:test").put("template","single_ring").put("metrics",JSONArray(listOf("short"))).toString())}
            }}finally{instrumentation.uiAutomation.dropShellPermissionIdentity()}
            for(style in BeaconUiStyle.entries){
                rule.runOnUiThread{rule.activity.setContent{BeaconTheme(style,BeaconThemeMode.LIGHT){
                    val bar=rememberBeaconBarBehavior()
                    BeaconScaffold(topBar={BeaconTopBar("小组件管理",false,bar,navigationIcon={BeaconIconButton(onClick={}){androidx.compose.material3.Icon(BeaconIcons.Back,"返回")}},actions={})},bottomBar={}){padding->WidgetManagerPage(Modifier.fillMaxSize().padding(padding),rememberLazyListState())}
                }}}
                rule.waitUntil(10000){rule.onAllNodesWithText("2×2 方卡").fetchSemanticsNodes().isNotEmpty()}
                val beforeRefresh = WidgetPerformance.requests.get()
                rule.onNodeWithTag("settings-list").performTouchInput { swipeDown(durationMillis=600) }
                rule.waitUntil(10000) { WidgetPerformance.requests.get() > beforeRefresh }
                rule.mainClock.advanceTimeBy(1500)
                rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("widget-instance:${ids[1]}"))
                rule.onNodeWithText("4×2 宽卡").assertExists()
                val folder=requireNotNull(context.getExternalFilesDir("state-qa"));folder.mkdirs()
                java.io.File(folder,"widget_manager_${style.name}.png").outputStream().use{rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            }
            // The editor must obtain host dimensions, not the old fixed 360x260 preview.
            val monitor=instrumentation.addMonitor(StateWidgetActivity::class.java.name,null,false)
            try{
                rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("widget-instance:${ids[0]}"))
                rule.onAllNodesWithText("管理测试额度").onFirst().performClick()
                val editor=monitor.waitForActivityWithTimeout(5000);assertNotNull(editor)
                assertEquals(ids[0],editor!!.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1))
                rule.waitUntil(10000){rule.onAllNodesWithText("2×2 · 180 × 180 dp · 桌面尺寸").fetchSemanticsNodes().isNotEmpty()}
                instrumentation.runOnMainSync{editor.finish()}
            }finally{instrumentation.removeMonitor(monitor)}
            host.deleteAppWidgetId(ids[0])
            rule.waitUntil(10000){!WidgetInstances.ids(context).contains(ids[0])}
            assertTrue(WidgetInstances.ids(context).contains(ids[1]))
        }finally{
            host.deleteHost();StateCache(context).use{cache->cache.forget(source);ids.forEach{cache.preference("widget:$it","")}}
        }
    }
}
