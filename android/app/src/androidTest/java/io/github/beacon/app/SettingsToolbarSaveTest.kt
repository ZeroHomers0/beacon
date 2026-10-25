package io.github.beacon.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ui.BeaconViewModel
import io.github.beacon.app.ui.components.SettingsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsToolbarSaveTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle) {
        val model = ViewModelProvider(rule.activity)[BeaconViewModel::class.java]
        val oldStyle = model.uiState.uiStyle
        rule.runOnUiThread{model.start(rule.activity)}
        rule.waitUntil(20_000) { model.state.value.runtimeConnected && model.state.value.receivePlanLoaded && !model.state.value.messageBusy }
        val temporaryConnection=model.state.value.configuredEndpoint.isBlank()
        if(temporaryConnection){
            rule.runOnUiThread{model.transport(TransportSettings("http://127.0.0.1:1","a".repeat(64)))}
            rule.waitUntil(15000){model.state.value.configuredEndpoint.isNotBlank()&&!model.state.value.messageBusy}
        }
        val originalPlan = model.state.value.receivePlan
        val originalPolling = model.state.value.pollingState.config
        var originalQuiet: QuietHours? = null
        try {
            rule.runOnUiThread {
                model.uiState.closeDetail(model)
                model.uiState.uiStyle = style
                model.uiState.page = 1
                model.uiState.navigateSettings(SettingsScreen.RECEPTION)
            }
            rule.waitForIdle()
            val save = rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_save))
            save.assertIsNotEnabled()
            assertTrue(save.getUnclippedBoundsInRoot().top.value < 100)
            rule.onNodeWithText("有未保存的更改").assertDoesNotExist()
            rule.onNodeWithText("设置已保存").assertDoesNotExist()
            val target = if (originalPolling.intervalMinutes == 30) 15 else 30
            rule.onNodeWithText(rule.activity.getString(R.string.receive_interval)).performScrollTo().performClick()
            rule.onAllNodesWithText(rule.activity.getString(R.string.settings_interval_minutes_option, target)).onLast().performClick()
            save.assertIsEnabled().performClick()
            rule.waitUntil(20_000) { !model.state.value.messageBusy && model.state.value.pollingState.config.intervalMinutes == target }
            rule.waitForIdle()
            save.assertIsNotEnabled()
            assertEquals(SettingsScreen.RECEPTION,model.uiState.settingsScreen)
            rule.onNodeWithText("设置已保存").assertDoesNotExist()

            rule.runOnUiThread { model.uiState.navigateSettings(SettingsScreen.NOTIFICATION_RULES) }
            rule.waitUntil(15_000) { model.uiState.quietHoursForm.loaded }
            rule.runOnUiThread { model.uiState.navigateSettings(SettingsScreen.QUIET_HOURS) }
            rule.waitUntil(15_000) { model.uiState.settingsScreen == SettingsScreen.QUIET_HOURS }
            rule.waitForIdle()
            originalQuiet = model.uiState.quietHoursForm.saved
            save.assertIsNotEnabled()
            rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).assertDoesNotExist()
            val day = if (originalQuiet!!.weekdays == 1) "周二" else "周一"
            rule.onNodeWithText(day).performScrollTo().performClick()
            save.assertIsEnabled()
            val weekday = rule.onNodeWithText(day).getUnclippedBoundsInRoot()
            assertEquals(48f, (weekday.bottom - weekday.top).value, 1f)
            save.performClick()
            rule.waitUntil(15_000) { !model.uiState.quietHoursForm.saving && !model.uiState.quietHoursForm.changed }
            rule.waitForIdle()
            save.assertIsNotEnabled()
            assertEquals(SettingsScreen.QUIET_HOURS,model.uiState.settingsScreen)
            rule.onNodeWithText("设置已保存").assertDoesNotExist()
        } finally {
            rule.runOnUiThread {
                model.saveReception(originalPlan, originalPolling)
                originalQuiet?.let { model.quietHours(it) {} }
                model.uiState.uiStyle = oldStyle
                model.uiState.navigateSettings(SettingsScreen.HOME)
                model.uiState.page = 0
            }
            rule.waitUntil(20_000) { !model.state.value.messageBusy }
            if(temporaryConnection){
                rule.runOnUiThread{model.clearConnection()}
                rule.waitUntil(15000){model.state.value.configuredEndpoint.isBlank()&&!model.state.value.messageBusy}
            }
        }
    }
    @Test fun toolbarSaveMaterial() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun toolbarSaveMiuix() = verify(BeaconUiStyle.MIUIX)
}

