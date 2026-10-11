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

class DeviceRevocationDialogTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        val form =
            ConnectionFormState().apply {
                endpoint = "https://example.invalid"
                bindingCode = "draft"
                connectionEdited = true
            }
        var revoked = 0
        var cleared = 0
        val actions =
            object : RuntimeActions by repository {
                override fun revokeDevice(onRevoked: () -> Unit) {
                    revoked++
                    onRevoked()
                }

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
                                configuredEndpoint = "https://example.invalid",
                            ),
                            actions,
                            form,
                        )
                    }
                }
            }
            rule.onNodeWithText("完整解绑当前设备").performClick()
            rule.onNodeWithText("取消").performClick()
            assertEquals(0, revoked)
            assertEquals(0, cleared)
            assertTrue(form.bindingCode.isNotEmpty())
            rule.onNodeWithText("完整解绑当前设备").performClick()
            rule.onNodeWithText("确认解绑").performClick()
            assertEquals(1, revoked)
            assertEquals(0, cleared)
            assertEquals("", form.endpoint)
            assertEquals("", form.bindingCode)
            assertFalse(form.connectionEdited)
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test
    fun materialRevocationIsExplicitAndDistinctFromLocalClear() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixRevocationIsExplicitAndDistinctFromLocalClear() = verify(BeaconUiStyle.MIUIX)
}
