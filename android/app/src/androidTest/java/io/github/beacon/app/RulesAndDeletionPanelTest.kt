package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.ReceptionFormState
import io.github.beacon.app.feature.settings.receptionSettingsItems
import io.github.beacon.app.feature.settings.StorageSettingsPanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RulesAndDeletionPanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        // Conditions edit the shared draft; saving belongs to the page toolbar.
        val form = ReceptionFormState()
        rule.runOnUiThread {
            form.reset(PollingState().config, ReceivePlan(ReceiveMode.RULES))
            rule.activity.setContent {
                BeaconTheme(style) {
                    LazyColumn(Modifier.testTag("reception-list")) {
                        receptionSettingsItems(
                            RuntimeState(receivePlanLoaded = true, runtimeConnected = true),
                            repository,
                            ReceivePlan(ReceiveMode.RULES),
                            form,
                            {},
                            {},
                            false,
                            PollingState(),
                        )
                    }
                }
            }
        }
        val battery = rule.activity.getString(R.string.settings_rules_battery)
        val schedule = rule.activity.getString(R.string.settings_rules_schedule)
        rule.onNodeWithTag("reception-list").performScrollToNode(hasText(battery))
        rule.onNodeWithContentDescription(battery).performClick()
        rule.onNodeWithTag("reception-list").performScrollToNode(hasText(schedule))
        rule.onNodeWithContentDescription(schedule).performClick()
        rule.runOnIdle {
            assertTrue(form.plan.lowBatteryEnabled)
            assertTrue(form.plan.scheduleEnabled)
            assertTrue(form.edited)
        }
        rule.onNodeWithTag("reception-list").performScrollToNode(hasText(battery))
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
            rule.onAllNodesWithText("清理本地消息").onLast().performScrollTo().performClick()
            rule.onNodeWithText("继续").performClick()
            assertEquals(0, cleared)
            rule.onNodeWithText("最后确认").assertExists()
            rule.onNodeWithText("取消").performClick()
            assertEquals(0, cleared)
            rule.onAllNodesWithText("清理本地消息").onLast().performScrollTo().performClick()
            rule.onNodeWithText("继续").performClick()
            rule.onNodeWithText("清理全部").performClick()
            assertEquals(1, cleared)
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialRulesAndTwoConfirmations() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixRulesAndTwoConfirmations() = verify(BeaconUiStyle.MIUIX)
}
