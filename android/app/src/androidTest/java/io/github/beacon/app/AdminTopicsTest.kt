package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.remote.ServerAdministration
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.feature.settings.AdminTopicsPage
import io.github.beacon.app.feature.settings.ServerAdminViewModel
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AdminTopicsTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun defaultSourceManagementWorksWithoutApplicationsInMiuix()=verify(BeaconUiStyle.MIUIX)
    @Test fun defaultSourceManagementWorksWithoutApplicationsInMaterial()=verify(BeaconUiStyle.MATERIAL3)

    private fun verify(style:BeaconUiStyle){
        val backend=TopicBackend();val model=ServerAdminViewModel{_,_->backend}
        rule.runOnUiThread{
            model.login("https://example.invalid","a".repeat(64))
            rule.activity.setContent{BeaconTheme(style){
                val state by model.state.collectAsState();val bar=rememberBeaconBarBehavior()
                BeaconScaffold(topBar={BeaconTopBar("数据主题",false,bar,navigationIcon={},actions={io.github.beacon.app.feature.settings.AdminTopicFilterAction(model.listFilters,state.devices,state.applications,!state.busy)})},bottomBar={}){padding->
                    if(state.loggedIn)AdminTopicsPage(state,model,rememberLazyListState(),Modifier.fillMaxSize().padding(padding))
                }
            }}
        }
        rule.waitUntil(10000){rule.onAllNodesWithText("无应用额度").fetchSemanticsNodes().isNotEmpty()}
        Thread.sleep(500)
        assertEquals("entering the list reuses overview topic cache",1,backend.listCalls.get())
        rule.onNodeWithContentDescription("筛选数据主题").performClick()
        rule.onNodeWithText("未关联应用",substring=false).performScrollTo().performClick()
        rule.waitUntil(10000){backend.query.get().has("application_id")&&!model.state.value.busy}
        assertEquals("",backend.query.get().getString("application_id"))
        assertTrue(model.state.value.applications.isEmpty())
        val title=rule.onNodeWithText("无应用额度").fetchSemanticsNode().boundsInRoot
        val more=rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_more_actions)).fetchSemanticsNode().boundsInRoot
        assertEquals("overflow menu aligns with first title line",title.center.y,more.center.y,2f)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_more_actions)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_view_details)).performClick()
        rule.waitUntil(10000){rule.onAllNodesWithText("剩余24%").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("关闭").performClick()
        val previousCalls=backend.listCalls.get()
        rule.onNodeWithTag("settings-list").performTouchInput{swipeDown(durationMillis=600)}
        rule.waitUntil(10000){backend.listCalls.get()>previousCalls&&!model.state.value.busy}
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        val folder=requireNotNull(rule.activity.getExternalFilesDir("state-qa"));folder.mkdirs()
        java.io.File(folder,"admin_topics_${style.name}.png").outputStream().use{rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_more_actions)).performClick()
        rule.onNodeWithText("清空当前状态").performClick()
        rule.onNodeWithText("清空",substring=false).performClick()
        rule.waitUntil(10000){backend.cleared&&!model.state.value.busy}
        assertEquals("",backend.clearedIdentity.getString("application_id"));assertEquals(3,backend.clearedIdentity.getInt("revision"))
    }

    private class TopicBackend:ServerAdministration {
        override val endpoint="https://example.invalid"
        val query=java.util.concurrent.atomic.AtomicReference(JSONObject())
        val listCalls=java.util.concurrent.atomic.AtomicInteger()
        @Volatile var cleared=false
        var clearedIdentity=JSONObject()
        override fun overview()=JSONObject("""{"server_id":"test-server","protocol":1,"storage":{"messages":0,"pending":0,"database_allocated_bytes":4096},"devices":[{"device_id":"phone","name":"测试手机","revoked":false}],"applications":[]}""")
        override fun stateTopics(query:JSONObject):JSONObject{
            listCalls.incrementAndGet()
            this.query.set(JSONObject(query.toString()))
            val row=JSONObject("""{"device_id":"phone","device_name":"测试手机","application_id":"","source_name":"默认来源","topic_key":"quota:a","title":"无应用额度","revision":3,"state":"active"}""")
            return JSONObject().put("topics",if(cleared)JSONArray()else JSONArray().put(row)).put("total",if(cleared)0 else 1).put("has_more",false)
        }
        override fun stateTopic(identity:JSONObject)=JSONObject().put("state",JSONObject("""{"topic_key":"quota:a","title":"无应用额度","body":"剩余24%","application_id":"","blocks":[]}"""))
        override fun clearStateTopic(identity:JSONObject):JSONObject{clearedIdentity=identity;cleared=true;return JSONObject().put("cleared",true)}
        override fun binding(name:String):JSONObject=error("unused")
        override fun revokeDevice(id:String):JSONObject=error("unused")
        override fun test(id:String,title:String,body:String,priority:Int):JSONObject=error("unused")
        override fun application(id:String,name:String,priority:Int):JSONObject=error("must not require an application")
        override fun revokeApplication(id:String):JSONObject=error("unused")
    }
}
