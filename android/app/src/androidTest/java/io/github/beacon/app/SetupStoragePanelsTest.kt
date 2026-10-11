package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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

class SetupStoragePanelsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        val state = mutableStateOf(RuntimeState(runtimeConnected = true))
        var checks = 0
        var syncs = 0
        var permissions = 0
        val actions =
            object : RuntimeActions by repository {
                override fun checkServer() {
                    checks++
                }

                override fun transport(
                    settings: TransportSettings?,
                    notifyOnly: Boolean,
                    onConfigured: (() -> Unit)?,
                ) {
                    syncs++
                }
            }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            SetupChecklistPanel(state.value, false, actions, { permissions++ })
                        }
                    }
                }
            }
            rule.onNodeWithText("立即同步").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithText("检查通知权限与渠道").performScrollTo().performClick()
            assertEquals(1, permissions)
            assertEquals(0, checks)
            assertEquals(0, syncs)
            rule.runOnUiThread {
                state.value =
                    state.value.copy(
                        configuredEndpoint = "https://example.invalid",
                        serverReachable = true,
                    )
            }
            rule.onNodeWithText("检测服务器").performScrollTo().performClick()
            rule.onNodeWithText("立即同步").performScrollTo().performClick()
            assertEquals(1, checks)
            assertEquals(1, syncs)
            rule.runOnUiThread { state.value = state.value.copy(transportBusy = true) }
            rule.onNodeWithText("立即同步").assertIsNotEnabled()
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            StorageSettingsPanel(
                                RuntimeState(
                                    runtimeConnected = true,
                                    cleanupPolicy = CleanupPolicy(),
                                    cleanupPreview =
                                        CleanupPreview(
                                            "opaque",
                                            4,
                                            0,
                                            0,
                                            4096,
                                            0,
                                            0,
                                            0,
                                            availableBytes = 10,
                                            sampledAtMs = 1,
                                        ),
                                ),
                                object : RuntimeActions by actions {
                                    override fun cleanupSettings(
                                        value: CleanupSettings?,
                                        onFailure: () -> Unit,
                                        onComplete: (CleanupSettings) -> Unit,
                                    ) {
                                        onComplete(CleanupSettings())
                                    }
                                },
                            )
                        }
                    }
                }
            }
            rule
                .onNodeWithText("消息尚未超过保留天数或数量上限。已读不代表可以立即清理，需先满足清理规则。")
                .performScrollTo()
                .assertIsDisplayed()
            rule.onNodeWithText("立即清理").assertIsNotEnabled()
            rule
                .onNodeWithText("剩余空间已低于或等于 100 MiB。", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            assertEquals(1, syncs)
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialReadinessAndStorageExplanations() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixReadinessAndStorageExplanations() = verify(BeaconUiStyle.MIUIX)
}
