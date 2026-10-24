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
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReceptionPageOrderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        val plan = mutableStateOf(ReceivePlan(ReceiveMode.POWER_SAVING))
        val form = ReceptionFormState()
        val choices = mutableListOf<ReceiveMode>()
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        LazyColumn(Modifier.testTag("reception")) {
                            receptionSettingsItems(
                                RuntimeState(runtimeConnected = true, receivePlanLoaded = true),
                                repository, plan.value, form,
                                { choices.add(it); plan.value = plan.value.copy(mode = it) }, {}, false, PollingState())
                        }
                    }
                }
            }
            rule.onNodeWithText(rule.activity.getString(R.string.settings_receive_status_title)).assertIsDisplayed()
            val statusTop = rule.onNodeWithText(rule.activity.getString(R.string.settings_receive_status_title))
                .getUnclippedBoundsInRoot().top
            val modeTitle = rule.activity.getString(R.string.settings_receive_mode_section)
            rule.onNodeWithTag("reception").performScrollToNode(hasText(modeTitle))
            assertTrue(rule.onNodeWithText(modeTitle).getUnclippedBoundsInRoot().top > statusTop)
            for (mode in listOf(ReceiveMode.REALTIME, ReceiveMode.RULES, ReceiveMode.POWER_SAVING)) {
                val label = rule.activity.getString(when (mode) {
                    ReceiveMode.REALTIME -> R.string.receive_mode_realtime
                    ReceiveMode.RULES -> R.string.receive_mode_rules
                    ReceiveMode.POWER_SAVING -> R.string.ui_power_saving_mode
                })
                rule.onNodeWithTag("reception").performScrollToNode(hasText(label) and hasClickAction())
                rule.onNode(hasText(label) and hasClickAction()).performClick().assertIsSelected()
                rule.onNode(isDialog()).assertDoesNotExist()
                rule.runOnIdle { assertEquals(mode, choices.last()) }
            }
            rule.runOnIdle { assertEquals(3, choices.size) }
        } finally { rule.runOnUiThread { repository.close() } }
    }
    @Test fun materialStatusFirstAndInlineModes() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixStatusFirstAndInlineModes() = verify(BeaconUiStyle.MIUIX)
}
