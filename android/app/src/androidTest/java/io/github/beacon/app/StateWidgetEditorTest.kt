package io.github.beacon.app

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.widget.StateWidgetActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StateWidgetEditorTest {
    @get:Rule val rule=createEmptyComposeRule()

    @Test fun editorHasSettingsHeaderAndVisibleSelectionsInBothStyles(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source="widget-editor-test"
        val styles=UiStyleStore(context);val previous=styles.load()
        val row=JSONObject().put("schema_version",1).put("topic_key","editor:test").put("application_id","")
            .put("revision",1).put("title","测试额度").put("body","剩余24%")
            .put("expires_at_ms",System.currentTimeMillis()+600000)
            .put("blocks",JSONArray("""[{"kind":"progress","id":"short","label":"短周期","value":24,"max":100,"unit":"%","tone":"critical"},{"kind":"progress","id":"long","label":"长周期","value":62,"max":100,"unit":"%"}]"""))
        StateCache(context).use{it.apply(source,listOf(row),"editor",true)}
        try{
            for(style in BeaconUiStyle.entries){
                styles.save(style)
                val intent=Intent(context,StateWidgetActivity::class.java).putExtra("source",source).putExtra("identity",":editor:test")
                ActivityScenario.launch<StateWidgetActivity>(intent).use{
                    rule.waitUntil(10000){rule.onAllNodesWithText("小组件设置").fetchSemanticsNodes().isNotEmpty()}
                    rule.onNodeWithText("导出模板").assertDoesNotExist()
                    rule.onNodeWithText("导入模板").assertDoesNotExist()
                    rule.onNodeWithText("导出状态快照").assertDoesNotExist()
                    rule.onNodeWithText("导入离线快照").assertDoesNotExist()
                    fun scrollTo(text:String){rule.onNodeWithTag("settings-list").performScrollToNode(hasText(text))}
                    rule.onNodeWithText("来源（可选）").assertDoesNotExist()
                    rule.onNodeWithText("状态主题").assertDoesNotExist()
                    scrollTo("按发送应用筛选")
                    rule.onNodeWithText("按发送应用筛选").performClick()
                    rule.onNodeWithText("未关联应用",substring=false).performClick()
                    rule.onNodeWithText("测试额度 · 未关联应用").assertExists()
                    scrollTo("选择展示内容")
                    rule.onNodeWithText("选择展示内容").assertExists()
                    scrollTo("未关联应用：直接使用设备推送密钥上报，无需创建应用。")
                    rule.onNodeWithText("未关联应用：直接使用设备推送密钥上报，无需创建应用。").assertExists()
                    scrollTo("按发送应用筛选")
                    val selectionFolder=requireNotNull(context.getExternalFilesDir("state-qa"));selectionFolder.mkdirs()
                    java.io.File(selectionFolder,"widget_data_selection_${style.name}.png").outputStream().use{stream->rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)}
                    scrollTo("显示内容")
                    rule.onNodeWithContentDescription("上移 短周期").assertIsNotEnabled().performTouchInput{click()}
                    rule.onNodeWithContentDescription("下移 短周期").assertIsEnabled().performClick()
                    rule.onNodeWithContentDescription("下移 短周期").assertIsNotEnabled().performTouchInput{click()}
                    rule.onNodeWithContentDescription("上移 短周期").assertIsEnabled()
                    assertTrue("metric order follows the move action",rule.onNodeWithText("长周期",substring=false).getUnclippedBoundsInRoot().top<rule.onNodeWithText("短周期",substring=false).getUnclippedBoundsInRoot().top)
                    scrollTo("内容配色")
                    val before=rule.onNodeWithText("内容配色").fetchSemanticsNode().boundsInRoot.height
                    rule.onNodeWithText("内容配色").performClick()
                    rule.onNodeWithText("按状态变色").performClick()
                    rule.onNodeWithText("按状态变色").assertExists()
                    val after=rule.onNodeWithText("内容配色").fetchSemanticsNode().boundsInRoot.height
                    assertEquals("selection must not change preference geometry",before,after,1f)
                    scrollTo("卡片背景")
                    rule.onNodeWithText("卡片背景").performClick()
                    rule.onNodeWithText("浅色",substring=false).performClick()
                    scrollTo("桌面预览")
                    val light=rule.onNodeWithContentDescription("组件预览").captureToImage().asAndroidBitmap()
                    scrollTo("卡片背景")
                    rule.onNodeWithText("卡片背景").performClick()
                    rule.onNodeWithText("深色",substring=false).performClick()
                    scrollTo("桌面预览")
                    val dark=rule.onNodeWithContentDescription("组件预览").captureToImage().asAndroidBitmap()
                    assertFalse("background choice must change the preview",light.sameAs(dark))
                    val folder=requireNotNull(context.getExternalFilesDir("state-qa"));folder.mkdirs()
                    java.io.File(folder,"widget_editor_${style.name}.png").outputStream().use{stream->rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)}
                    scrollTo("卡片背景")
                    rule.onNodeWithText("卡片背景").performClick()
                    rule.onNodeWithText("跟随手机主题").performClick()
                    scrollTo(context.getString(R.string.widget_display_style))
                    java.io.File(folder,"widget_templates_${style.name}.png").outputStream().use{stream->rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)}
                    // Back uses the same unsaved-change dialog, never an inserted banner.
                    rule.onNodeWithContentDescription("返回").performClick()
                    rule.onNodeWithText("放弃修改？").assertExists()
                    rule.onNodeWithText("继续编辑").performClick()
                }
                ActivityScenario.launch<StateWidgetActivity>(Intent(context,StateWidgetActivity::class.java).putExtra("source",source).putExtra("identity",":editor:test").putExtra("detail",true)).use{
                    rule.waitUntil(10000){rule.onAllNodesWithText("内容详情").fetchSemanticsNodes().isNotEmpty()}
                    rule.onNodeWithTag("settings-list").performScrollToNode(hasText("通知设置"))
                    rule.onNodeWithText("通知主内容").assertExists()
                    val folder=requireNotNull(context.getExternalFilesDir("state-qa"));folder.mkdirs()
                    java.io.File(folder,"widget_detail_${style.name}.png").outputStream().use{stream->rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)}
                }
            }
        }finally{
            styles.save(previous)
            StateCache(context).use{it.forget(source)}
        }
    }
}
