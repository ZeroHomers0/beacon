package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.feature.messages.StateListCard
import io.github.beacon.app.feature.messages.StructuredBlocksPanel
import io.github.beacon.app.feature.messages.StatesPage
import io.github.beacon.app.feature.messages.StatesFilterAction
import io.github.beacon.app.data.local.StateCache
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import io.github.beacon.app.widget.WidgetMoreSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReviewUiRegressionTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()

    @Test fun longDataPreviewStaysTwoLinesInMaterial()=verifyPreview(BeaconUiStyle.MATERIAL3)
    @Test fun longDataPreviewStaysTwoLinesInMiuix()=verifyPreview(BeaconUiStyle.MIUIX)
    private fun verifyPreview(style: BeaconUiStyle) {
        var opened=false
        val row=JSONObject().put("title","长正文主题").put("body","很长的新闻正文".repeat(3000))
        rule.runOnUiThread { rule.activity.setContent { BeaconTheme(style) { StateListCard(row){opened=true} } } }
        rule.waitForIdle()
        val layouts=mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("state-preview",useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.all{it.lineCount<=2})
        rule.onNode(hasClickAction() and hasText("长正文主题",substring=true)).performClick()
        rule.runOnIdle { assertTrue(opened) }
    }

    @Test fun countdownExpiresWhileTheDetailRemainsOpen() {
        val target=System.currentTimeMillis()+2000
        rule.runOnUiThread { rule.activity.setContent { BeaconTheme(BeaconUiStyle.MATERIAL3) {
            StructuredBlocksPanel("""[{"kind":"countdown","id":"reset","target_at_ms":$target}]""")
        } } }
        rule.waitUntil(8000){rule.onAllNodesWithText("已到期").fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun textListDoesNotOfferIneffectiveNumericSettings() {
        val row=JSONObject("""{"blocks":[{"kind":"list","id":"news","items":[{"id":"one","text":"新闻"}]}]}""")
        rule.runOnUiThread { rule.activity.setContent { BeaconTheme(BeaconUiStyle.MIUIX) {
            Column { WidgetMoreSettings(row,JSONObject("""{"template":"text_list","metrics":["news"]}""")){} }
        } } }
        rule.onNodeWithText("列表颜色").performClick()
        rule.onNodeWithText("小数位数").assertDoesNotExist()
        rule.onNodeWithText("单位位置").assertDoesNotExist()
        rule.onNodeWithText("显示趋势箭头").assertDoesNotExist()
    }

    @Test fun dataListSearchStaysPinnedAndFilterWorksInMaterial()=verifyList(BeaconUiStyle.MATERIAL3)
    @Test fun dataListSearchStaysPinnedAndFilterWorksInMiuix()=verifyList(BeaconUiStyle.MIUIX)
    private fun verifyList(style: BeaconUiStyle) {
        val source="review-list-fixture"
        val context=rule.activity
        StateCache(context).use{cache->cache.apply(source,(1..12).map{index->
            JSONObject().put("schema_version",1).put("application_id","").put("topic_key","review-$index").put("revision",1)
                .put("title","review:主题$index").put("body","新闻摘要内容".repeat(100)).put("blocks",org.json.JSONArray())
        },"review",true)}
        try {
            rule.runOnUiThread { rule.activity.setContent { BeaconTheme(style) {
                var hidden by remember { mutableStateOf(false) }
                val bar=rememberBeaconBarBehavior()
                BeaconScaffold(topBar={BeaconTopBar("数据",false,bar,navigationIcon={},actions={StatesFilterAction(hidden){hidden=it}})},bottomBar={}) { padding ->
                    StatesPage(Modifier.fillMaxSize().padding(padding),showHidden=hidden,onShowHidden={hidden=it})
                }
            } } }
            rule.waitUntil(10000){rule.onAllNodesWithTag("states-list").fetchSemanticsNodes().isNotEmpty()}
            rule.onNode(hasSetTextAction()).performTextInput("review:")
            val before=rule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
            rule.onNodeWithTag("states-list").performTouchInput{swipeUp()}
            assertEquals(before,rule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithContentDescription("筛选数据").performClick()
            rule.onNodeWithText("包含已隐藏主题").performClick()
            rule.onNodeWithText("包含已隐藏主题").assertExists()
            val folder=requireNotNull(rule.activity.getExternalFilesDir("review-fixed"));folder.mkdirs()
            val file=java.io.File(folder,"states_${style.name}.png")
            file.outputStream().use{rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            for(command in listOf("mkdir -p /data/local/tmp/beacon-review-fixed","cp ${file.absolutePath} /data/local/tmp/beacon-review-fixed/${file.name}")){
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}
            }
        } finally { StateCache(context).use{it.forget(source)} }
    }
}
