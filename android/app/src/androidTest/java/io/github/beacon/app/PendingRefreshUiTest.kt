package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test

class PendingRefreshUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        var calls = 0
        var complete: ((Long) -> Unit)? = null
        var fail: (() -> Unit)? = null
        val actions = object : RuntimeActions by repository {
            override fun pendingReminders(onFailure: () -> Unit, onReady: (Long) -> Unit) {
                calls++
                fail = onFailure
                if (calls == 1) onReady(3) else complete = onReady
            }
        }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        SettingsPage(SettingsScreen.NOTIFICATIONS, rememberLazyListState(), Modifier.fillMaxSize(),
                            PaddingValues(16.dp), RuntimeState(runtimeConnected = true), actions,
                            remember { ConnectionFormState() }, false, NotificationPermissionState.ALLOWED, null,
                            style, BeaconThemeMode.LIGHT, {}, ReceivePlan(), remember { ReceptionFormState() },
                            {}, {}, {}, {}, {}, {}, {}, remember { UpdateCheckState() }, {})
                    }
                }
            }
            rule.waitUntil { calls == 1 }
            rule.mainClock.autoAdvance = false
            val spinning = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate)
            rule.onAllNodes(spinning).assertCountEquals(0)
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_refresh_status)).performClick()
            rule.mainClock.advanceTimeBy(64)
            rule.onNode(hasContentDescription(rule.activity.getString(R.string.action_refresh_status)) and spinning).assertExists()
            rule.runOnIdle { complete!!(2) }
            rule.mainClock.advanceTimeBy(400)
            rule.onAllNodes(spinning).assertCountEquals(0)
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_refresh_status)).performClick()
            rule.mainClock.advanceTimeBy(64)
            rule.runOnIdle { fail!!() }
            rule.mainClock.advanceTimeBy(400)
            rule.onNodeWithText(rule.activity.getString(R.string.settings_reminders_failed)).assertIsDisplayed()
            rule.onAllNodes(spinning).assertCountEquals(0)
        } finally { rule.runOnUiThread { repository.close() } }
    }
    @Test fun miuixPendingRefreshAnimates() = verify(BeaconUiStyle.MIUIX)
    @Test fun materialPendingRefreshAnimates() = verify(BeaconUiStyle.MATERIAL3)
}
