package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.StorageSettingsPanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        var applied = ""
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

                override fun applyCleanup(token: String) {
                    applied = token
                }
            }
        try {
            val state =
                RuntimeState(
                    runtimeConnected = true,
                    cleanupPolicy = CleanupPolicy(),
                    cleanupPreview = CleanupPreview("preview", 10, 2, 1, 4096, 3, 3, 3),
                )
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            StorageSettingsPanel(state, actions)
                        }
                    }
                }
            }
            rule.onNodeWithText("立即清理").performScrollTo().performClick()
            rule.onNodeWithText("清理本地消息？").assertExists()
            rule
                .onNodeWithText(rule.activity.getString(R.string.storage_confirm_message, 3))
                .assertExists()
            rule.onNodeWithText("取消").performClick()
            assertEquals("", applied)
            rule.onNodeWithText("立即清理").performScrollTo().performClick()
            rule.onAllNodesWithText("立即清理").onLast().performClick()
            assertEquals("preview", applied)
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialPreviewAndConfirmation() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixPreviewAndConfirmation() = verify(BeaconUiStyle.MIUIX)
    private fun customSaveFailure(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        var fail: (() -> Unit)? = null
        var finish: ((CleanupSettings) -> Unit)? = null
        var requested: CleanupSettings? = null
        var requests = 0
        val actions = object : RuntimeActions by repository {
            override fun cleanupSettings(value: CleanupSettings?, onFailure: () -> Unit, onComplete: (CleanupSettings) -> Unit) {
                if (value == null) onComplete(CleanupSettings())
                else {
                    requests++
                    requested = value
                    fail = onFailure
                    finish = onComplete
                }
            }
            override fun previewCleanup(policy: CleanupPolicy) {}
        }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            StorageSettingsPanel(RuntimeState(runtimeConnected = true, status = OperationStatus.result("预期保存失败")), actions)
                        }
                    }
                }
            }
            rule.onNodeWithText(rule.activity.getString(R.string.storage_custom)).performScrollTo().performClick()
            rule.onNodeWithText("保存").assertIsNotEnabled()
            val newDays = if (CleanupPolicy().days == 14) "15" else "14"
            rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(newDays)
            rule.onNodeWithText("保存").assertIsEnabled().performClick()
            rule.onNodeWithText("保存").assertIsNotEnabled()
            rule.onNodeWithText("取消").assertIsNotEnabled()
            rule.onNodeWithText(newDays).assertIsNotEnabled()
            assertEquals(1, requests)
            rule.runOnIdle { fail!!() }
            rule.onNodeWithText("预期保存失败").assertIsDisplayed()
            rule.onAllNodes(hasSetTextAction()).onFirst().assertTextContains(newDays)
            rule.onNodeWithText("保存").assertIsEnabled().performClick()
            assertEquals(2, requests)
            rule.runOnIdle { finish!!(requested!!) }
            rule.onNodeWithText("保存").assertDoesNotExist()
        } finally { rule.runOnUiThread { repository.close() } }
    }
    @Test fun materialCustomFailurePreservesDraft() = customSaveFailure(BeaconUiStyle.MATERIAL3)
    @Test fun miuixCustomFailurePreservesDraft() = customSaveFailure(BeaconUiStyle.MIUIX)

}
