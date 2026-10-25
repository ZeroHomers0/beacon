package io.github.beacon.app

import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import io.github.beacon.app.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetExpansionTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private val context get()=rule.activity.applicationContext
    private fun row()=JSONObject().put("title","账户概况").put("body","完整摘要").put("topic_key","widget:expansion")
        .put("application_id","").put("revision",1).put("accepted_at_ms",System.currentTimeMillis())
        .put("blocks",JSONArray("""[{"kind":"progress","id":"short","label":"短周期剩余额度","value":24,"max":100,"unit":"%"},{"kind":"progress","id":"long","label":"长周期剩余额度","value":62,"max":100,"unit":"%"},{"kind":"progress","id":"month","label":"月度剩余额度","value":80,"max":100,"unit":"%"},{"kind":"list","id":"news","label":"任务列表","items":[{"id":"1","text":"备份任务完成，可以查看详细日志"},{"id":"2","text":"服务器健康检查正常"},{"id":"3","text":"长标题新闻与榜单内容示例，不得超出卡片"}]}]"""))
        .apply{getJSONArray("blocks").put(JSONObject().put("kind","countdown").put("id","reset").put("label","额度重置").put("target_at_ms",System.currentTimeMillis()+7_500_000))}
    private fun config(type:String,ids:List<String>)=JSONObject().put("template",type).put("metrics",JSONArray(ids))
        .put("short_labels",JSONObject().put("short","5h").put("long","7d").put("month","月度"))

    @Test fun renderNewLayoutsAtEverySupportedSizeAndTheme(){
        val folder=requireNotNull(context.getExternalFilesDir("expanded-widget-qa"));folder.mkdirs()
        for(format in WidgetFormat.entries)for(dark in listOf(false,true))for(type in listOf("single_ring","ring_grid","segmented_ring","nested_rings","countdown","overview_bars","overview_rings","overview_list")) {
            val ids=when(type){"single_ring"->listOf("short");"segmented_ring"->listOf("disk");"countdown"->listOf("reset");"overview_list"->listOf("short","news");else->listOf("short","long","month")}
            val data=row()
            if(type=="segmented_ring")data.put("blocks",JSONArray("""[{"kind":"segments","id":"disk","label":"空间分布","total":100,"unit":"GB","items":[{"id":"a","label":"图片","value":30},{"id":"b","label":"视频","value":50},{"id":"c","label":"其他","value":10}]}]"""))
            val config=config(type,ids).put("format",format.name).put("label_scale",110).put("number_scale",115).put("stroke_scale",115)
                .put("background",if(dark)"dark"else"light")
            if(type.startsWith("overview")&&format!=WidgetFormat.LARGE){assertNotNull(WidgetConfigRules.layoutError(row(),config,format.width,format.height));continue}
            assertNull("$type $format",WidgetConfigRules.layoutError(data,config,format.width,format.height))
            val bitmap=WidgetRenderer.render(data,config,format.width,format.height,dark,3f)
            assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(0,0)))
            assertTrue(bitmap.byteCount<=2097152*4)
            val file=java.io.File(folder,"${type}_${format}_${dark}.png")
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            copy(file)
        }
    }
    private fun copy(file:java.io.File){
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        for(command in listOf("mkdir -p /data/local/tmp/beacon-widget-expansion","cp ${file.absolutePath} /data/local/tmp/beacon-widget-expansion/${file.name}"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}
    }
    @Test fun optionalTapOpensReadOnlyContentAndKeepsEditingOut(){
        val appContext=context
        val source="widget-expansion-detail";val id=9871
        val config=config("nested_rings",listOf("short","long","month")).put("source",source).put("identity",":widget:expansion")
        assertNull(WidgetTapIntent.intent(appContext,id,config))
        config.put("tap_action","detail")
        val intent=requireNotNull(WidgetTapIntent.intent(appContext,id,config))
        assertEquals(WidgetContentActivity::class.java.name,intent.component!!.className)
        assertEquals(id,intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,0))
        StateCache(appContext).use{cache->cache.apply(source,listOf(row()),"1",false);cache.preference("widget:$id",config.toString())}
        try {
            ActivityScenario.launch<WidgetContentActivity>(intent).use {
                rule.waitUntil(10000){rule.onAllNodesWithText("完整摘要").fetchSemanticsNodes().isNotEmpty()}
                rule.onNodeWithText("编辑小组件").assertDoesNotExist()
                rule.onNodeWithText("完整摘要").assertIsDisplayed()
            }
        }finally{StateCache(appContext).use{it.preference("widget:$id","");it.forget(source)}}
    }
    private fun verifySettings(style:BeaconUiStyle,mode:BeaconThemeMode){
        var saved=JSONObject()
        rule.runOnUiThread{rule.activity.setContent{BeaconTheme(style,mode){
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)){
            var draft by remember(style,mode){mutableStateOf(config("nested_rings",listOf("short","long","month")))}
            BeaconSettingsList(rememberLazyListState(),Modifier.fillMaxSize().safeDrawingPadding()) {
                item{WidgetDisplaySettings(row(),draft){draft=it;saved=it}}
            }
        }}}}
        rule.onNodeWithText("不执行操作").performClick()
        rule.onNodeWithText("查看完整内容",substring=false).performClick()
        rule.runOnIdle{assertEquals("detail",saved.getString("tap_action"))}
        rule.onNodeWithText("5h",substring=false).performScrollTo().performTextReplacement("123456789")
        rule.runOnIdle{assertEquals("12345678",saved.getJSONObject("short_labels").getString("short"))}
        rule.waitForIdle()
        val file=java.io.File(context.getExternalFilesDir("expanded-widget-qa"),"settings_${style}_${mode}_2x.png")
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();copy(file)
    }
    @Test fun settingsUseExistingMaterialControls(){for(mode in listOf(BeaconThemeMode.LIGHT,BeaconThemeMode.DARK))verifySettings(BeaconUiStyle.MATERIAL3,mode)}
    @Test fun settingsUseExistingMiuixControls(){for(mode in listOf(BeaconThemeMode.LIGHT,BeaconThemeMode.DARK))verifySettings(BeaconUiStyle.MIUIX,mode)}
}
