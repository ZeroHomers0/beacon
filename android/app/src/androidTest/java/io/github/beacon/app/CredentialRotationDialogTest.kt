package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.theme.BeaconTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CredentialRotationDialogTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        val form =
            ConnectionFormState().apply {
                bindingCode = "draft"
                connectionEdited = true
            }
        var submitted: String? = null
        val actions =
            object : RuntimeActions by repository {
                override fun rotateCredentials(payload: String, onRotated: () -> Unit) {
                    submitted = payload
                    onRotated()
                }
            }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        CredentialRotationPanel(
                            RuntimeState(
                                runtimeConnected = true,
                                configuredEndpoint = "https://example.invalid",
                            ),
                            actions,
                            form,
                        )
                    }
                }
            }
            rule.onNodeWithText("导入轮换结果").performClick()
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("invalid-secret")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.waitForIdle()
            rule.onNodeWithText("校验并更新").performClick()
            rule
                .onNodeWithText("格式无效或超过 4KiB，请粘贴完整的设备轮换 JSON。")
                .performScrollTo()
                .assertIsDisplayed()
            assertNull(submitted)
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithText("导入轮换结果").performClick()
            rule.onNodeWithText("校验并更新").assertIsNotEnabled()
            val payload =
                JSONObject()
                    .put("server_id", "a".repeat(64))
                    .put("device_id", "b".repeat(64))
                    .put("receive_token", "c".repeat(64))
                    .put("push_key", "d".repeat(64))
                    .toString()
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement(payload)
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("x".repeat(4097))
            rule.onNodeWithText("校验并更新").assertIsNotEnabled()
            assertNull(submitted)
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement(payload)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.waitForIdle()
            rule.onNodeWithText("校验并更新").performClick()
            assertEquals(payload, submitted)
            assertEquals("", form.bindingCode)
            assertFalse(form.connectionEdited)
            assertEquals("https://example.invalid", form.endpoint)
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialRotationDialogValidationCancelAndSubmit() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixRotationDialogValidationCancelAndSubmit() = verify(BeaconUiStyle.MIUIX)
}
