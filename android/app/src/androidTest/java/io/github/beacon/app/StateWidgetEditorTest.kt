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
            .put("blocks",JSONArray("""[{"kind":"progress","id":"short","label":"短周期","value":24,"max":100,"unit":"%","tone":"critical"}]"""))
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
                    rule.onNodeWithText("固定配色").performScrollTo().assertIsSelected()
                    rule.onNodeWithText("风险配色").performClick().assertIsSelected()
                    rule.onNodeWithText("固定配色").assertIsNotSelected()
                    rule.onNodeWithText("浅色",substring=false).performScrollTo().performClick().assertIsSelected()
                    rule.onNodeWithContentDescription("组件预览").performScrollTo()
                    val light=rule.onNodeWithContentDescription("组件预览").captureToImage().asAndroidBitmap()
                    rule.onNodeWithText("深色",substring=false).performScrollTo().performClick().assertIsSelected()
                    rule.onNodeWithContentDescription("组件预览").performScrollTo()
                    val dark=rule.onNodeWithContentDescription("组件预览").captureToImage().asAndroidBitmap()
                    assertFalse("background choice must change the preview",light.sameAs(dark))
                    val folder=requireNotNull(context.getExternalFilesDir("state-qa"));folder.mkdirs()
                    java.io.File(folder,"widget_editor_${style.name}.png").outputStream().use{stream->rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)}
                    rule.onNodeWithText("跟随系统").performScrollTo().performClick().assertIsSelected()
                    rule.onNodeWithText("深色",substring=false).assertIsNotSelected()
                }
            }
        }finally{
            styles.save(previous)
            StateCache(context).use{it.forget(source)}
        }
    }
}
