package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ui.components.NotificationPermissionState
import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.theme.BeaconTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsHomeGuideOrderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val prefs = rule.activity.getSharedPreferences("first_use_guide", 0)
        val original = prefs.getBoolean("completed", false)
        val repository = RuntimeRepository()
        val runtime = mutableStateOf(RuntimeState(configuredEndpoint = "https://example.invalid"))
        val connection = ConnectionFormState()
        val reception = ReceptionFormState()
        val update = UpdateCheckState()
        var destination: SettingsScreen? = null
        lateinit var scroll: LazyListState
        lateinit var scope: CoroutineScope
        try {
            rule.runOnUiThread {
                prefs.edit().putBoolean("completed", false).commit()
                rule.activity.setContent {
                    scroll = rememberLazyListState()
                    scope = rememberCoroutineScope()
                    BeaconTheme(style) {
                        SettingsPage(
                            screen = SettingsScreen.HOME, scroll = scroll,
                            modifier = Modifier.fillMaxSize().testTag("settings-home-order"),
                            padding = PaddingValues(16.dp), runtimeState = runtime.value,
                            actions = repository, connectionForm = connection, connectionDirty = false,
                            notificationPermission = NotificationPermissionState.ALLOWED, notificationHint = null,
                            uiStyle = style, themeMode = BeaconThemeMode.SYSTEM,
                            onNavigate = { destination = it }, receivePlan = ReceivePlan(), receptionForm = reception,
                            onShowReceiveModes = {}, onUiStyleChange = {}, onThemeModeChange = {},
                            onOperationDetails = {}, onFeedback = {}, onRequestNotificationPermission = {},
                            onNotificationSettingsUnavailable = {}, updateState = update, onCheckUpdate = {},
                        )
                    }
                }
            }
            rule.onNodeWithText(rule.activity.getString(R.string.ui_v2_server_config_title)).assertIsDisplayed()
            rule.runOnUiThread { prefs.edit().putBoolean("completed", true).apply() }
            rule.waitForIdle()
            rule.onNodeWithText(rule.activity.getString(R.string.ui_v2_server_config_title)).assertIsDisplayed()
            rule.onNodeWithText(rule.activity.getString(R.string.ui_v2_server_config_title)).assertIsDisplayed()
            val guide = rule.activity.getString(R.string.settings_guide_title)
            rule.onNodeWithTag("settings-home-order").performScrollToNode(hasText(guide))
            rule.onNodeWithText(guide).performClick()
            assertEquals(SettingsScreen.GUIDE, destination)
            rule.runOnUiThread {
                runtime.value = runtime.value.copy(configuredEndpoint = "")
                scope.launch { scroll.scrollToItem(0) }
            }
            rule.waitForIdle()
            rule.onNodeWithText(rule.activity.getString(R.string.ui_v2_server_config_title)).assertIsDisplayed()
        } finally {
            rule.runOnUiThread {
                prefs.edit().putBoolean("completed", original).apply()
                repository.close()
            }
        }
    }

    @Test fun guideRemainsAccessibleAfterCompletionMaterial() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun guideMovesAfterCompletionMiuix() = verify(BeaconUiStyle.MIUIX)
}
