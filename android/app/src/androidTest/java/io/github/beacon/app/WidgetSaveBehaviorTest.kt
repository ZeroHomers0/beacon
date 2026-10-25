package io.github.beacon.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.widget.StateWidgetActivity
import io.github.beacon.app.widget.WidgetFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetSaveBehaviorTest {
    @get:Rule val rule=createEmptyComposeRule()
    @Test fun firstAndExistingSavesStayInEditorAndResetDirtyState(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val styles=UiStyleStore(context);val previous=styles.load();val source="widget-save-behavior"
        val row=JSONObject().put("schema_version",1).put("application_id","").put("topic_key","save:test").put("revision",1)
            .put("title","测试保存").put("body","24%").put("expires_at_ms",System.currentTimeMillis()+600000)
            .put("blocks",JSONArray("""[{"kind":"progress","id":"short","label":"短周期","value":24,"max":100}]"""))
        val manager=AppWidgetManager.getInstance(context);val host=AppWidgetHost(context,20421109);val ids=mutableListOf<Int>()
        StateCache(context).use{it.apply(source,listOf(row),"save",true)}
        try {
            for(style in BeaconUiStyle.entries)for(newConfig in listOf(true,false)){
                styles.save(style)
                val id=host.allocateAppWidgetId();ids+=id
                instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                try{assertTrue(manager.bindAppWidgetIdIfAllowed(id,WidgetFormat.SQUARE.component(context),Bundle().apply{
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,180)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,180)
                }))}finally{instrumentation.uiAutomation.dropShellPermissionIdentity()}
                if(!newConfig)StateCache(context).use{it.preference("widget:$id",JSONObject().put("source",source).put("identity",":save:test")
                    .put("template","single_ring").put("metrics",JSONArray(listOf("short"))).toString())}
                val intent=Intent(context,StateWidgetActivity::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("source",source).putExtra("identity",":save:test")
                ActivityScenario.launch<StateWidgetActivity>(intent).use{scenario->
                    rule.waitUntil(10000){rule.onAllNodesWithText("小组件设置").fetchSemanticsNodes().isNotEmpty()}
                    val save=rule.onNodeWithContentDescription("保存设置")
                    if(newConfig){
                        save.assertIsEnabled().performClick()
                        rule.waitForIdle();scenario.onActivity{assertFalse(it.isFinishing)}
                        rule.onAllNodesWithText("小组件设置").onFirst().assertExists();save.assertIsNotEnabled()
                    }else save.assertIsNotEnabled()
                    rule.onNodeWithTag("settings-list").performScrollToNode(hasSetTextAction())
                    val name=rule.onAllNodes(hasSetTextAction()).onFirst()
                    name.performTextReplacement("我的组件");save.assertIsEnabled().performClick()
                    rule.waitForIdle();scenario.onActivity{assertFalse(it.isFinishing)}
                    rule.onAllNodesWithText("小组件设置").onFirst().assertExists();save.assertIsNotEnabled()
                    StateCache(context).use{assertEquals("我的组件",JSONObject(it.preference("widget:$id")).getString("instance_name"))}
                    name.performTextReplacement("临时名称");save.assertIsEnabled()
                    name.performTextReplacement("我的组件");save.assertIsNotEnabled()
                }
            }
        } finally {
            host.deleteHost();styles.save(previous)
            StateCache(context).use{cache->cache.forget(source);ids.forEach{cache.preference("widget:$it","")}}
        }
    }
}
