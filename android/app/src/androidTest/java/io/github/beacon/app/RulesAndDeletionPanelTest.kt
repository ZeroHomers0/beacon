package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.ReceiveRulesPanel
import io.github.beacon.app.feature.settings.StorageSettingsPanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RulesAndDeletionPanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        var saved: ReceivePlan? = null
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ReceiveRulesPanel(ReceivePlan(ReceiveMode.RULES), false) { saved = it }
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("低电量时使用省电接收").performScrollTo().performClick()
        rule.onNodeWithContentDescription("启用时间段").performScrollTo().performClick()
        rule.onNodeWithText("保存规则").performScrollTo().performClick()
        assertTrue(requireNotNull(saved).lowBatteryEnabled)
        assertTrue(requireNotNull(saved).scheduleEnabled)
        rule.onNodeWithContentDescription("低电量时使用省电接收").performScrollTo()
        rule.waitForIdle()
        val screenshot =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot()
        val screenshotFile =
            java.io.File(rule.activity.getExternalFilesDir(null), "receive-rules-${style.name}.png")
        screenshotFile.outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        val repository = RuntimeRepository()
        var cleared = 0
        val actions =
            object : RuntimeActions by repository {
                override fun cleanupSettings(
                    value: CleanupSettings?,
                    onFailure: () -> Unit,
                    onComplete: (CleanupSettings) -> Unit,
                ) {
                    onComplete(value ?: CleanupSettings())
                }

                override fun previewCleanup(policy: CleanupPolicy) {}

                override fun clearAllMessages() {
                    cleared++
                }
            }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            StorageSettingsPanel(RuntimeState(runtimeConnected = true), actions)
                        }
                    }
                }
            }
            rule.onAllNodesWithText("清理所有本地消息").onLast().performScrollTo().performClick()
            rule.onNodeWithText("继续").performClick()
            assertEquals(0, cleared)
            rule.onNodeWithText("最后确认").assertExists()
            rule.onNodeWithText("取消").performClick()
            assertEquals(0, cleared)
            rule.onAllNodesWithText("清理所有本地消息").onLast().performScrollTo().performClick()
            rule.onNodeWithText("继续").performClick()
            rule.onNodeWithText("确认清理全部").performClick()
            assertEquals(1, cleared)
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialRulesAndTwoConfirmations() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixRulesAndTwoConfirmations() = verify(BeaconUiStyle.MIUIX)
}
