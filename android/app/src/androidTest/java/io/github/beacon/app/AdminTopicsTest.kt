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
                BeaconScaffold(topBar={BeaconTopBar("状态主题",false,bar,navigationIcon={},actions={})},bottomBar={}){padding->
                    if(state.loggedIn)AdminTopicsPage(state,model,rememberLazyListState(),Modifier.fillMaxSize().padding(padding))
                }
            }}
        }
        rule.waitUntil(10000){rule.onAllNodesWithText("无应用额度").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("来源",substring=false))
        rule.onNodeWithText("来源",substring=false).performClick();rule.onNodeWithText("默认来源",substring=false).performClick()
        rule.waitUntil(10000){backend.query.get().has("application_id")&&!model.state.value.busy}
        assertEquals("",backend.query.get().getString("application_id"))
        assertTrue(model.state.value.applications.isEmpty())
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("查看最新数据"))
        rule.onNodeWithText("查看最新数据").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithText("剩余24%").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("关闭").performClick()
        val folder=requireNotNull(rule.activity.getExternalFilesDir("state-qa"));folder.mkdirs()
        java.io.File(folder,"admin_topics_${style.name}.png").outputStream().use{rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("清空当前状态"))
        rule.onNodeWithText("清空当前状态").performClick()
        rule.onNodeWithText("清空",substring=false).performClick()
        rule.waitUntil(10000){backend.cleared&&!model.state.value.busy}
        assertEquals("",backend.clearedIdentity.getString("application_id"));assertEquals(3,backend.clearedIdentity.getInt("revision"))
    }

    private class TopicBackend:ServerAdministration {
        override val endpoint="https://example.invalid"
        val query=java.util.concurrent.atomic.AtomicReference(JSONObject())
        @Volatile var cleared=false
        var clearedIdentity=JSONObject()
        override fun overview()=JSONObject("""{"server_id":"test-server","protocol":1,"storage":{"messages":0,"pending":0,"database_allocated_bytes":4096},"devices":[{"device_id":"phone","name":"测试手机","revoked":false}],"applications":[]}""")
        override fun stateTopics(query:JSONObject):JSONObject{
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
