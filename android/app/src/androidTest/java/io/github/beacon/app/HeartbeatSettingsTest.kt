package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
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
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        LazyColumn(Modifier.testTag("reception-list")) {
                            receptionSettingsItems(
                                RuntimeState(receivePlanLoaded = true),
                                repository,
                                plan.value,
                                ReceptionFormState(),
                                { plan.value = it },
                                {},
                                {},
                                false,
                                PollingState(),
                            )
                        }
                    }
                }
            }
            rule.onNodeWithText("心跳间隔").performScrollTo().performClick()
            rule.onNodeWithText("180 秒").performClick()
            rule.runOnIdle { assertEquals(180, plan.value.heartbeatSeconds) }
            rule.onNodeWithText("心跳间隔").performScrollTo().performClick()
            rule.onNodeWithText("30 秒").performClick()
            rule.runOnIdle {
                assertEquals(30, plan.value.heartbeatSeconds)
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
