package io.github.beacon.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.StateListCard
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ListPresentationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name:String) {
        // Android RenderThread ripple timing is independent of Compose's test clock.
        android.os.SystemClock.sleep(400)
        rule.waitForIdle()
        val image=rule.onRoot().captureToImage().asAndroidBitmap()
        val file=java.io.File(rule.activity.getExternalFilesDir(null),"$name.png")
        file.outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        val copy=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use{it.readBytes()}
    }
    private fun verify(style:BeaconUiStyle) {
        for(kind in 0..4) {
            var status by mutableIntStateOf(0)
            var opened=0
            rule.runOnUiThread {
                rule.activity.enableEdgeToEdge()
                rule.activity.setContent {
                    BeaconTheme(style,if(kind==4)BeaconThemeMode.DARK else BeaconThemeMode.LIGHT) {
                        BeaconScaffold(topBar={BeaconTopBar(listOf("消息","设备管理","应用管理","数据主题","数据主题")[kind],true,rememberBeaconBarBehavior(),{}, {})},bottomBar={}) { insets ->
                            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                if(kind in 1..2) AdminListFilters("",{},status,{status=it},if(kind==1)"搜索设备名称或 ID"else"搜索应用名称或设备",
                                    total=5,active=3,application=kind==2,
                                    totalIcon=if(kind==1)io.github.beacon.app.ui.icons.BeaconIcons.Device else io.github.beacon.app.ui.icons.BeaconIcons.Applications)
                                else {
                                    BeaconSearchBox("",{},if(kind==0)"搜索消息"else"搜索主题或应用",onSearch={})
                                    if(kind==0)BeaconListSwitch(listOf("全部 3","未读 1"),listOf(io.github.beacon.app.ui.icons.BeaconIcons.Mail,io.github.beacon.app.ui.icons.BeaconIcons.Messages),0,onSelected={})
                                    else BeaconListSummary("共 3 个主题")
                                }
                                val base=rememberLazyListState()
                                val scroll=rememberScopedListState("$kind:$status",base)
                                LazyColumn(state=scroll,verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                                    if(kind==0)items(3) { index ->
                                        MessageItem(IncomingMessage("synthetic","m$index",listOf("构建已完成，产物已上传","每日简报：接收服务与桌面小组件运行状态汇总","服务已恢复")[index],
                                            if(index==2)""else"所有任务正常完成，可查看构建报告。标题与摘要使用不同层级，标签保留中性底色。",
                                            createdAtMs=1794105000000,isRead=index!=0,applicationId="ci",applicationName="CI 构建",group="发布通知"),
                                            false,false,{}, {}, {},false,{opened++},{},{})
                                    }
                                    else if(kind in 1..2)items((0..4).filter{status==0 || (it>=3)==(status==2)}) { index ->
                                        AdminListCard(if(kind==1)"设备 $index"else"应用 $index",
                                            if(kind==1)"8f31a2b9c0$index"else"所属设备：工作手机",
                                            if(index>=3)if(kind==1)"已解绑"else"令牌已撤销" else if(kind==1)"已绑定"else"令牌有效",
                                            true,emptyList(),revoked=index>=3,priority=if(kind==2)5 else null,onDetails={opened++})
                                    }
                                    else items(3) { index ->
                                        val row=JSONObject().put("title",listOf("构建产物上传进度","今日任务数量","尚未上报的主题")[index])
                                            .put("application_name","CI 构建").put("accepted_at_ms",1794105000000)
                                            .put("_waiting",index==2).put("body","当前任务运行正常，等待下一次更新。")
                                            .put("blocks",org.json.JSONArray(if(index==0)"""[{"kind":"progress","label":"上传","value":72,"min":0,"max":100,"unit":"%"}]"""else if(index==1)"""[{"kind":"metric","label":"完成","value":8,"unit":"项"}]"""else"[]"))
                                        StateListCard(row){opened++}
                                    }
                                }
                            }
                        }
                    }
                }
            }
            rule.waitForIdle()
            if(kind in 1..2) {
                rule.onNodeWithText("已${if(kind==1)"解绑"else"撤销"} 2").performClick().assertIsSelected()
                rule.runOnIdle{assertEquals(2,status)}
                rule.onNodeWithText("全部 5").performClick().assertIsSelected()
                rule.mainClock.advanceTimeBy(600)
            }
            val title=when(kind){0->"构建已完成，产物已上传";1->"设备 0";2->"应用 0";else->"构建产物上传进度"}
            rule.onNodeWithText(title).performClick()
            rule.runOnIdle{assertEquals(1,opened)}
            if(kind in 1..2) {
                rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.admin_more_actions)).onFirst().performClick()
                rule.runOnIdle{assertEquals("Menu must not also open the card",1,opened)}
                rule.onNodeWithText(rule.activity.getString(R.string.settings_view_details)).performClick()
                rule.runOnIdle{assertEquals(2,opened)}
            }
            rule.mainClock.advanceTimeBy(600)
            capture("lists-$style-$kind")
        }
    }
    @Test fun materialLists()=verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixLists()=verify(BeaconUiStyle.MIUIX)

    @Test fun compactSelectionTitleFitsNarrowScreen() {
        for (style in BeaconUiStyle.entries) {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        Box(Modifier.width(320.dp)) {
                            BeaconTopBar("已选 100 条", true, rememberBeaconBarBehavior(), prioritizeTitle = true,
                                navigationIcon = { BeaconActionIcon(io.github.beacon.app.ui.icons.BeaconIcons.Close, "退出多选") {} },
                                actions = {
                                    BeaconActionIcon(io.github.beacon.app.ui.icons.BeaconIcons.More, "更多操作") {}
                                })
                        }
                    }
                }
            }
            rule.onNodeWithText("已选 100 条").assertIsDisplayed()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue(get(results))
                    assertTrue("Selected count must not be ellipsized in $style: ${results.map { "${it.size} font=${it.layoutInput.style.fontSize} overflowW=${it.didOverflowWidth} overflowH=${it.didOverflowHeight} ellipsis=${it.isLineEllipsized(0)}" }}", results.all { !it.isLineEllipsized(0) && it.getLineEnd(0) == it.layoutInput.text.length && it.getLineRight(0) <= it.size.width + 1f })
                }
        }
    }

    @Test fun returningFromShortFilterRestoresLongListPosition() {
        var filter by mutableStateOf("all")
        var detail by mutableStateOf(false)
        var active:androidx.compose.foundation.lazy.LazyListState?=null
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(BeaconUiStyle.MATERIAL3) {
                    val transition = androidx.compose.animation.core.updateTransition(
                        if (detail) IncomingMessage("synthetic", "detail", "详情", "") else null,
                        label = "list-navigation",
                    )
                    BeaconMessageTransition(transition, animateSize = false) { message ->
                    if (message == null) {
                    val base=rememberLazyListState()
                    val scroll=rememberScopedListState(filter,base)
                    SideEffect{active=scroll}
                    LazyColumn(state=scroll,modifier=Modifier.fillMaxSize().testTag("position-list")) {
                        items(if(filter=="all")40 else 2){BeaconText("条目 $it",Modifier.height(64.dp))}
                    }
                    } else BeaconText("详情")
                    }
                }
            }
        }
        rule.onNodeWithTag("position-list").performScrollToIndex(15)
        rule.runOnIdle{assertEquals(15,active!!.firstVisibleItemIndex);filter="short"}
        rule.waitForIdle()
        rule.runOnIdle{assertEquals(0,active!!.firstVisibleItemIndex);filter="all"}
        rule.waitForIdle()
        rule.runOnIdle{assertEquals(15,active!!.firstVisibleItemIndex);detail=true}
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onNodeWithTag("position-list").assertDoesNotExist()
        rule.runOnIdle{detail=false}
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.runOnIdle{assertEquals(15,active!!.firstVisibleItemIndex)}
    }
}
