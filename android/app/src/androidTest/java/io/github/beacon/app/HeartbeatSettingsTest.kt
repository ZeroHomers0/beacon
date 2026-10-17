package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.ReceptionFormState
import io.github.beacon.app.feature.settings.receptionSettingsItems
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HeartbeatSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val plan = mutableStateOf(ReceivePlan(ReceiveMode.REALTIME))
        val repository = RuntimeRepository()
        // This list edits the page draft; the app toolbar persists it.
        val form = ReceptionFormState()
        try {
            rule.runOnUiThread {
                form.reset(PollingState().config, plan.value)
                rule.activity.setContent {
                    BeaconTheme(style) {
                        LazyColumn(Modifier.testTag("reception-list")) {
                            receptionSettingsItems(
                                RuntimeState(receivePlanLoaded = true),
                                repository,
                                plan.value,
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
            rule.onNodeWithContentDescription("自适应心跳").performScrollTo().performClick()
            rule.onNodeWithText("心跳间隔").performScrollTo().performClick()
            rule.onNodeWithText("180 秒").performClick()
            rule.runOnIdle {
                assertEquals(180, form.plan.heartbeatSeconds)
                assertFalse(form.plan.adaptiveHeartbeat)
            }
            rule.onNodeWithTag("reception-list").performScrollToNode(hasText("心跳间隔"))
            rule.onNodeWithText("心跳间隔").performClick()
            rule.onNodeWithText("30 秒").performClick()
            rule.runOnIdle {
                assertEquals(30, form.plan.heartbeatSeconds)
            }
            rule.onNodeWithTag("reception-list").performScrollToNode(hasContentDescription("普通消息批量接收"))
            rule.onNodeWithContentDescription("普通消息批量接收").performClick()
            rule.runOnIdle {
                assertTrue(form.plan.batchOrdinaryMessages)
                plan.value = plan.value.copy(mode = ReceiveMode.RULES)
            }
            rule.onNodeWithTag("reception-list").performScrollToNode(hasText("心跳间隔"))
            rule.onNodeWithText("心跳间隔").assertExists()
            rule.runOnIdle { plan.value = plan.value.copy(mode = ReceiveMode.POWER_SAVING) }
            rule.onNodeWithText("心跳间隔").assertDoesNotExist()
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialHeartbeatChoices() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixHeartbeatChoices() = verify(BeaconUiStyle.MIUIX)
}
