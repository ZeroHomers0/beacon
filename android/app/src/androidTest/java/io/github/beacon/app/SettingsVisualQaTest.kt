package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test

/** Real settings panels with deterministic data, no credentials or external delivery. */
class SettingsVisualQaTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        val bitmap = rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val file = java.io.File(rule.activity.getExternalFilesDir("ui-qa"), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /data/local/tmp/beacon-ui-qa",
            "cp ${file.absolutePath} /data/local/tmp/beacon-ui-qa/${file.name}")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    private fun verify(style: BeaconUiStyle, mode: BeaconThemeMode) {
        val repository = RuntimeRepository()
        val actions = object : RuntimeActions by repository {
            override fun cleanupSettings(value: CleanupSettings?, onFailure: () -> Unit, onComplete: (CleanupSettings) -> Unit) {
                onComplete(value ?: CleanupSettings(automatic = true, lastResult = "2026-10-24 08:01:02 · 已清理 12345 条本地消息"))
            }
            override fun previewCleanup(policy: CleanupPolicy) {}
            override fun pendingReminders(onFailure: () -> Unit, onReady: (Long) -> Unit) = onReady(12)
            override fun notificationSettings(value: NotificationSettings?, onFailure: () -> Unit, onComplete: (NotificationSettings) -> Unit) {
                onComplete(value ?: NotificationSettings())
            }
            override fun quietHours(value: QuietHours?, onFailure: () -> Unit, onComplete: (QuietHours) -> Unit) {
                onComplete(value ?: QuietHours(enabled = true))
            }
        }
        val plan = ReceivePlan(mode = ReceiveMode.RULES)
        val state = RuntimeState(configuredEndpoint = "https://very-long-server-name.example.com",
            runtimeConnected = true, connectionSummaryLoaded = true, serverReachable = true,
            serverCheckedAtMs = System.currentTimeMillis(), receivePlan = plan, receivePlanLoaded = true,
            realtimeRunning = true, pollingState = PollingState(config = PollingConfig(enabled = true),
                lastOutcome = SyncOutcome.OK, lastSuccessMs = System.currentTimeMillis(), lastSuccessInserted = 12345),
            cleanupPolicy = CleanupPolicy(), cleanupPreviewFresh = true,
            cleanupPreview = CleanupPreview("fixture", 1234567, 200, 10, 123456789, 1234, 100, 1234))
        try {
            for (scale in listOf(1f, 2f)) {
                for (screen in listOf(SettingsScreen.CONNECTION, SettingsScreen.RECEPTION, SettingsScreen.STORAGE,
                    SettingsScreen.BACKGROUND, SettingsScreen.NOTIFICATIONS, SettingsScreen.NOTIFICATION_RULES,
                    SettingsScreen.BACKUP, SettingsScreen.QUIET_HOURS, SettingsScreen.GUIDE, SettingsScreen.ABOUT)) {
                    rule.runOnUiThread {
                        rule.activity.setContent {
                            BeaconTheme(style, mode) {
                                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                        Column(Modifier.fillMaxSize().systemBarsPadding()) {
                                            BeaconText(rule.activity.getString(screen.titleRes), Modifier.padding(16.dp),
                                                style = MaterialTheme.typography.titleLarge)
                                            SettingsPage(screen = screen, scroll = rememberLazyListState(), modifier = Modifier.weight(1f),
                                                padding = PaddingValues(16.dp), runtimeState = state, actions = actions,
                                                connectionForm = remember { ConnectionFormState().apply { deviceName = "Xiaomi 17 Pro Max" } },
                                                connectionDirty = false, notificationPermission = NotificationPermissionState.ALLOWED,
                                                notificationHint = null, uiStyle = style, themeMode = mode, onNavigate = {},
                                                receivePlan = plan, receptionForm = remember { ReceptionFormState() },
                                                onSelectReceiveMode = {}, onUiStyleChange = {}, onThemeModeChange = {},
                                                onOperationDetails = {}, onFeedback = {}, onRequestNotificationPermission = {},
                                                onNotificationSettingsUnavailable = {}, updateState = remember { UpdateCheckState() }, onCheckUpdate = {})
                                        }
                                    }
                                }
                            }
                        }
                    }
                    rule.waitForIdle()
                    rule.onNodeWithTag(SETTINGS_LIST_TAG).assertExists()
                    capture("${style}_${mode}_${scale}_${screen}")
                    if (screen in setOf(SettingsScreen.RECEPTION, SettingsScreen.STORAGE,
                            SettingsScreen.QUIET_HOURS, SettingsScreen.GUIDE)) {
                        repeat(3) { rule.onNodeWithTag(SETTINGS_LIST_TAG).performTouchInput { swipeUp() } }
                        capture("${style}_${mode}_${scale}_${screen}_end")
                    }
                }
            }
        } finally { rule.runOnUiThread { repository.close() } }
    }
    @Test fun materialLight() = verify(BeaconUiStyle.MATERIAL3, BeaconThemeMode.LIGHT)
    @Test fun materialDark() = verify(BeaconUiStyle.MATERIAL3, BeaconThemeMode.DARK)
    @Test fun miuixLight() = verify(BeaconUiStyle.MIUIX, BeaconThemeMode.LIGHT)
    @Test fun miuixDark() = verify(BeaconUiStyle.MIUIX, BeaconThemeMode.DARK)
}
