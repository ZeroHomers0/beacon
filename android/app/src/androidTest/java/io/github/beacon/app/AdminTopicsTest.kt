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
    @Test fun topicMutationCommitsOnceWhenTheFollowingReadFails() {
        val backend=TopicBackend(true)
        val model=ServerAdminViewModel{_,_->backend}
        rule.runOnUiThread { model.login("https://example.invalid","a".repeat(64)) }
        rule.waitUntil(10000){model.state.value.loggedIn&&!model.state.value.busy}
        for(action in listOf("create","update","delete")) {
            var completed=false
            backend.failReadsAfterMutation=true
            backend.failReads=false
            rule.runOnUiThread {
                model.mutateTopic(action,JSONObject().put("device_id","phone").put("topic_key","quota").put("name","主题"),JSONObject()) { completed=true }
            }
            rule.waitUntil(10000){!model.state.value.busy}
            rule.runOnIdle { assertTrue(completed) }
            assertEquals(null,model.state.value.actionFailure)
            assertNotNull(model.state.value.topicsFailure)
            assertEquals(R.string.admin_topic_refresh_after_success_failed,model.state.value.feedback)
        }
        assertEquals(3,backend.mutations)
        backend.failReads=false
        rule.runOnUiThread { model.loadTopics(JSONObject()) }
        rule.waitUntil(10000){!model.state.value.busy}
        assertNull(model.state.value.topicsFailure)
        assertEquals(3,backend.mutations)
    }

    @Test fun clearCommitsOnceWhenTheFollowingReadFails() {
        val backend=TopicBackend(false)
        val model=ServerAdminViewModel{_,_->backend}
        rule.runOnUiThread { model.login("https://example.invalid","a".repeat(64)) }
        rule.waitUntil(10000){model.state.value.loggedIn&&!model.state.value.busy}
        backend.failReads=true
        var completed=false
        rule.runOnUiThread { model.clearTopic(JSONObject(),JSONObject()){completed=true} }
        rule.waitUntil(10000){!model.state.value.busy}
        rule.runOnIdle { assertTrue(completed) }
        assertTrue(backend.cleared)
        assertNull(model.state.value.actionFailure)
        assertNotNull(model.state.value.topicsFailure)
    }
    @Test fun destructiveOverviewMutationDoesNotReplayWhenRefreshFails() {
        val backend=TopicBackend(true)
        val model=ServerAdminViewModel{_,_->backend}
        rule.runOnUiThread{model.login("https://example.invalid","a".repeat(64))}
        rule.waitUntil(10000){model.state.value.loggedIn&&!model.state.value.busy}
        backend.failOverviewAfterMutation=true
        for(operation in listOf<()->Unit>({model.clearMessages()},{model.deleteDevice("phone")},{model.deleteApplication("application")})) {
            rule.runOnUiThread{operation()}
            rule.waitUntil(10000){!model.state.value.busy}
            assertNull(model.state.value.actionFailure)
            assertNull(model.state.value.awaiting)
            assertNotNull(model.state.value.refreshFailure)
            assertEquals(R.string.admin_topic_refresh_after_success_failed,model.state.value.feedback)
        }
        assertEquals(3,backend.overviewMutations)
    }

    @Test fun defaultSourceManagementWorksWithoutApplicationsInMiuix()=verify(BeaconUiStyle.MIUIX)
    @Test fun defaultSourceManagementWorksWithoutApplicationsInMaterial()=verify(BeaconUiStyle.MATERIAL3)

    @Test fun precreatedTopicsUseExistingControlsInMiuix()=verifyCatalog(BeaconUiStyle.MIUIX)
    @Test fun precreatedTopicsUseExistingControlsInMaterial()=verifyCatalog(BeaconUiStyle.MATERIAL3)
    private fun verifyCatalog(style:BeaconUiStyle){
        val backend=TopicBackend(true);val model=ServerAdminViewModel{_,_->backend}
        rule.runOnUiThread{
            model.login("https://example.invalid","a".repeat(64))
            rule.activity.setContent{BeaconTheme(style){val state by model.state.collectAsState();val bar=rememberBeaconBarBehavior()
                BeaconScaffold(topBar={BeaconTopBar("数据主题",false,bar,navigationIcon={},actions={
                    io.github.beacon.app.feature.settings.AdminTopicFilterAction(model.listFilters,state.devices,state.applications,!state.busy)
                    BeaconActionIcon(io.github.beacon.app.ui.icons.BeaconIcons.Add,"新建数据主题",!state.busy,onClick=model::showCreation)
                })},bottomBar={}){padding->if(state.loggedIn)AdminTopicsPage(state,model,rememberLazyListState(),Modifier.fillMaxSize().padding(padding))}
            }}
        }
        rule.waitUntil(10000){model.state.value.topicsLoaded&&!model.state.value.busy}
        rule.onNodeWithContentDescription("新建数据主题").performClick()
        rule.onNodeWithText("创建",substring=false).assertIsNotEnabled()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("topic-name"))).performTextInput("预创建主题")
        rule.onNodeWithText("创建",substring=false).assertIsEnabled()
        val buttons=rule.onAllNodes((hasText("取消") or hasText("创建")) and hasClickAction()).fetchSemanticsNodes()
        // Read both positions in one frame; the IME can move the MIUIX dialog between reads.
        rule.runOnIdle{
            val cancelBounds=buttons.single{it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any{value->value.text=="取消"}}.boundsInRoot
            val createBounds=buttons.single{it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any{value->value.text=="创建"}}.boundsInRoot
            assertEquals(cancelBounds.width,createBounds.width,1f)
            assertEquals(cancelBounds.center.y,createBounds.center.y,1f)
        }
        val folder=requireNotNull(rule.activity.getExternalFilesDir("state-qa"));folder.mkdirs()
        java.io.File(folder,"topic_create_${style.name}.png").outputStream().use{androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        rule.onNodeWithText("创建",substring=false).performClick()
        rule.waitUntil(10000){backend.catalogRow!=null&&!model.state.value.busy}
        rule.onNodeWithText("等待数据",substring=false).assertExists()
        val title=rule.onNodeWithText("预创建主题").fetchSemanticsNode().boundsInRoot
        val more=rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_more_actions)).fetchSemanticsNode().boundsInRoot
        assertEquals(title.center.y,more.center.y,2f)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_more_actions)).performClick()
        rule.onNodeWithText("修改名称",substring=false).performClick()
        rule.onNodeWithText("保存",substring=false).assertIsNotEnabled()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("topic-name"))).performTextReplacement("新主题名称")
        rule.onNodeWithText("保存",substring=false).performClick()
        rule.waitUntil(10000){backend.catalogRow?.optString("name")=="新主题名称"&&!model.state.value.busy}
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_more_actions)).performClick()
        rule.onNodeWithText("删除主题",substring=false).performClick()
        rule.onNodeWithText("删除",substring=false).performClick()
        rule.waitUntil(10000){backend.catalogRow==null&&!model.state.value.busy}
        rule.onNodeWithText("新主题名称",substring=false).assertDoesNotExist()
    }

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
        rule.onNodeWithText(rule.activity.getString(R.string.admin_topic_clear)).performClick()
        rule.onNodeWithText("清空",substring=false).performClick()
        rule.waitUntil(10000){backend.cleared&&!model.state.value.busy}
        assertEquals("",backend.clearedIdentity.getString("application_id"));assertEquals(3,backend.clearedIdentity.getInt("revision"))
    }

    private class TopicBackend(private val catalog:Boolean=false):ServerAdministration {
        @Volatile var failReads=false
        @Volatile var failReadsAfterMutation=false
        @Volatile var mutations=0
        override val endpoint="https://example.invalid"
        val query=java.util.concurrent.atomic.AtomicReference(JSONObject())
        val listCalls=java.util.concurrent.atomic.AtomicInteger()
        @Volatile var cleared=false
        var clearedIdentity=JSONObject()
        @Volatile var catalogRow:JSONObject?=null
        @Volatile var failOverviewAfterMutation=false
        @Volatile var failOverview=false
        var overviewMutations=0
        override fun clearMessages():JSONObject {overviewMutations++;failOverview=failOverviewAfterMutation;return JSONObject()}
        override fun deleteDevice(id:String):JSONObject {overviewMutations++;failOverview=failOverviewAfterMutation;return JSONObject()}
        override fun deleteApplication(id:String):JSONObject {overviewMutations++;failOverview=failOverviewAfterMutation;return JSONObject()}
        override fun overview():JSONObject {
            if(failOverview)throw java.io.IOException("read failed")
            return overviewResult()
        }
        private fun overviewResult()=JSONObject("""{"server_id":"test-server","protocol":1,"storage":{"messages":0,"pending":0,"database_allocated_bytes":4096},"devices":[{"device_id":"phone","name":"测试手机","revoked":false}],"applications":[]}""").put("topic_catalog_protocol",if(catalog)1 else 0)
        override fun topics(query:JSONObject):JSONObject {
            if(failReads)throw java.io.IOException("read failed")
            return JSONObject().put("topics",catalogRow?.let{JSONArray().put(it)}?:JSONArray()).put("total",if(catalogRow==null)0 else 1).put("has_more",false)
        }
        override fun mutateTopic(action:String,payload:JSONObject):JSONObject{
            catalogRow=when(action){
                "create"->JSONObject(payload.toString()).put("topic_key",payload.optString("topic_key").ifBlank{"generated"}).put("title",payload.getString("name")).put("device_name","测试手机").put("source_name","未关联应用").put("config_version",1).put("revision",0).put("state","waiting")
                "update"->JSONObject(catalogRow!!.toString()).put("title",payload.getString("name")).put("name",payload.getString("name")).put("config_version",2)
                "delete"->null
                else->error(action)
            };mutations++;if(failReadsAfterMutation)failReads=true
            return JSONObject().put("topic",catalogRow)
        }
        override fun stateTopics(query:JSONObject):JSONObject{
            if(failReads)throw java.io.IOException("read failed")
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
