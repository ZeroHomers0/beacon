package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConfigurationDialogsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        var cleared = 0
        var saved: CleanupPolicy? = null
        val form =
            ConnectionFormState().apply {
                endpoint = "https://example.invalid"
                bindingCode = "old"
                connectionEdited = true
            }
        val actions =
            object : RuntimeActions by repository {
                override fun clearConnection(onCleared: () -> Unit) {
                    cleared++
                    onCleared()
                }
            }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        ClearConnectionPanel(
                            RuntimeState(
                                runtimeConnected = true,
                                configuredEndpoint = form.endpoint,
                            ),
                            actions,
                            form,
                        )
                    }
                }
            }
            rule.onNodeWithText("清除本机配置").performClick()
            rule.onNodeWithText("取消").performClick()
            assertEquals(0, cleared)
            rule.onNodeWithText("清除本机配置").performClick()
            rule.onNodeWithText("确认清除").performClick()
            assertEquals(1, cleared)
            assertEquals("", form.endpoint)
            assertEquals("", form.bindingCode)
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        CustomCleanupDialog(CleanupPolicy(), false, {}, { saved = it })
                    }
                }
            }
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("14")
            rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("0")
            rule.onNodeWithText("保存").performClick()
            rule.onNodeWithText("天数需为 0–3650，上限需为 1–100000 的整数").assertIsDisplayed()
            assertNull(saved)
            rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("12")
            rule.onNodeWithText("保存").performClick()
            assertEquals(CleanupPolicy(14, 12, true), saved)
            rule.runOnUiThread {
                rule.activity.setContent { BeaconTheme(style) { NotificationChannelsPanel() } }
            }
            rule.onNodeWithText("新消息提醒").assertIsDisplayed()
            rule.onNodeWithText("静音与历史消息").assertIsDisplayed()
            rule.onNodeWithText(rule.activity.getString(R.string.channel_runtime)).assertIsDisplayed()
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialConfirmationAndCustomInputs() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixConfirmationAndCustomInputs() = verify(BeaconUiStyle.MIUIX)
}
