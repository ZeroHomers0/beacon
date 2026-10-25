package io.github.beacon.app

import android.content.*
import android.os.IBinder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.data.remote.MessageIdentity
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.RuntimeControlClient
import io.github.beacon.app.runtime.RuntimeProbeService
import io.github.beacon.app.support.LoopbackServer
import io.github.beacon.app.widget.StateWidgetActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WidgetTopicSyncTest {
    @get:Rule val rule=createEmptyComposeRule()
    @Test fun editorFetchesUnreportedTopicsWithoutPollingAndRetainsChoicesOnFailure() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val connected=CountDownLatch(1);var binder:IBinder?=null
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,service:IBinder){binder=service;connected.countDown()}
            override fun onServiceDisconnected(name:ComponentName){}
        }
        val styles=UiStyleStore(context);val previousStyle=styles.load()
        val serverId="b".repeat(64);val device="c".repeat(64)
        val namespace=MessageIdentity.namespace(serverId,device)
        LoopbackServer(201,"{}").use{server->
            server.responses["/api/v1/capabilities"]=200 to JSONObject(LoopbackServer.DEVICE_SUMMARY).put("topic_catalog_protocol",1).toString()
            val topic=JSONObject().put("application_id","").put("topic_key","brand-new").put("name","全新未上报主题").put("config_version",1)
            val reply=JSONObject().put("server_id",serverId).put("topics",JSONArray().put(topic)).put("next_cursor","catalog-1").put("has_more",false).toString()
            assertTrue(context.bindService(Intent(context,RuntimeProbeService::class.java),connection,Context.BIND_AUTO_CREATE))
            try{
                assertTrue(connected.await(15,TimeUnit.SECONDS))
                val control=RuntimeControlClient(requireNotNull(binder))
                assertEquals(SyncOutcome.OK,control.transport(TransportSettings(server.endpoint,"d".repeat(64)),false).outcome)
                control.setPolling(PollingConfig(enabled=false))
                StateCache(context).use{it.forget(namespace)}
                for(style in BeaconUiStyle.entries){
                    styles.save(style);server.paths.clear()
                    server.responses["/api/v1/topics"]=200 to reply
                    ActivityScenario.launch<StateWidgetActivity>(Intent(context,StateWidgetActivity::class.java)).use{
                        rule.waitUntil(10000){StateCache(context).use{cache->cache.find(namespace,":brand-new")?.optBoolean("_waiting")==true}}
                        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("选择展示内容"))
                        rule.onNodeWithText("选择展示内容").performClick()
                        rule.onAllNodesWithText("全新未上报主题 · 未关联应用").onLast().performClick()
                        rule.waitUntil(10000){rule.onAllNodes(hasText("已同步当前绑定设备的主题",substring=true)).fetchSemanticsNodes().isNotEmpty()}
                        assertEquals(1,server.paths.count{it=="/api/v1/topics"})
                        assertFalse("Catalog selection must not receive messages",server.paths.any{it.startsWith("/api/v1/messages")})
                        assertFalse(control.state().config.enabled)
                        server.responses["/api/v1/topics"]=503 to "{}"
                        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("同步主题"))
                        rule.onNodeWithText("同步主题").performClick()
                        rule.waitUntil(10000){rule.onAllNodes(hasText("主题同步未完成",substring=true)).fetchSemanticsNodes().isNotEmpty()}
                        rule.onNodeWithText("全新未上报主题 · 未关联应用").assertExists()
                        assertTrue(StateCache(context).use{cache->cache.find(namespace,":brand-new")?.optBoolean("_waiting")==true})
                    }
                }
            }finally{
                binder?.let{RuntimeControlClient(it).clearConnection()}
                StateCache(context).use{it.forget(namespace)}
                styles.save(previousStyle);context.unbindService(connection)
            }
        }
    }
}
