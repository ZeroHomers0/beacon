package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.BarkEncryptionPanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BarkEncryptionSettingsTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun verify(style:BeaconUiStyle) {
        val repository=RuntimeRepository()
        var configured=false
        var attempts=0
        var saved:Pair<String,String>?=null
        var feedback=""
        val actions=object:RuntimeActions by repository {
            override fun barkEncryptionConfigured(onComplete:(Boolean?)->Unit) { onComplete(configured) }
            override fun barkEncryption(key:String,iv:String,onFailure:(String)->Unit,onComplete:(String)->Unit) {
                attempts++
                if(attempts==1){onFailure("保存未确认，请重试");return}
                configured=key.isNotEmpty();saved=key to iv;onComplete("已保存")
            }
        }
        try {
            rule.runOnUiThread{rule.activity.setContent{BeaconTheme(style){
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BarkEncryptionPanel(RuntimeState(runtimeConnected=true,configuredEndpoint="https://example.invalid"),actions){feedback=it}
                }
            }}}
            rule.onNodeWithText("未配置").assertExists()
            rule.onNodeWithText("配置解密密钥").performClick()
            rule.onNodeWithText("保存",substring=false).assertIsNotEnabled()
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("bad")
            rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("1".repeat(16))
            rule.onNodeWithText("保存",substring=false).assertIsNotEnabled()
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("k".repeat(16))
            rule.onNodeWithText("保存",substring=false).performClick()
            rule.onNodeWithText("保存未确认，请重试").assertExists()
            assertEquals(1,attempts)
            rule.onNodeWithText("保存",substring=false).performClick()
            rule.onNodeWithText("已配置，可重新设置").assertExists()
            assertEquals("k".repeat(16) to "1".repeat(16),saved)
            assertEquals("已保存",feedback)
            rule.onNodeWithText("配置解密密钥").performClick()
            rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().forEach {
                assertEquals("",it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            }
            val folder=requireNotNull(rule.activity.getExternalFilesDir("state-qa"));folder.mkdirs()
            rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(200)
            java.io.File(folder,"bark_decryption_${style.name}.png").outputStream().use {
                requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    .compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            rule.onNodeWithText("取消",substring=false).performClick()
            rule.onNodeWithText("移除解密配置",substring=false).performClick()
            rule.onNodeWithText("取消",substring=false).performClick()
            assertTrue(configured)
            rule.onNodeWithText("移除解密配置",substring=false).performClick()
            rule.onNodeWithText("移除",substring=false).performClick()
            rule.onNodeWithText("未配置").assertExists()
            assertEquals("" to "",saved)
        } finally { repository.stop() }
    }
    @Test fun materialConfiguresRetriesAndRemoves()=verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixConfiguresRetriesAndRemoves()=verify(BeaconUiStyle.MIUIX)
}
