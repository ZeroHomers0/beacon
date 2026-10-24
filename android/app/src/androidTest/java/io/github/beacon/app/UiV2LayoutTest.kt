package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic state only: screenshots never contain saved credentials or real messages. */
class UiV2LayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val current = mutableStateOf(SettingsScreen.HOME)
    private val selectedStyle = mutableStateOf(BeaconUiStyle.MATERIAL3)
    private val selectedMode = mutableStateOf(BeaconThemeMode.LIGHT)
    private val quiet = QuietHoursFormState()
    private var savedQuiet = QuietHours()

    private fun actions(repository: RuntimeRepository) = object : RuntimeActions by repository {
        override fun quietHours(value: QuietHours?, onFailure: () -> Unit, onComplete: (QuietHours) -> Unit) {
            if (value != null) savedQuiet = value
            onComplete(savedQuiet)
        }
        override fun notificationSettings(value: NotificationSettings?, onFailure: () -> Unit, onComplete: (NotificationSettings) -> Unit) {
            onComplete(value ?: NotificationSettings())
        }
        override fun cleanupSettings(value: CleanupSettings?, onFailure: () -> Unit, onComplete: (CleanupSettings) -> Unit) {
            onComplete(value ?: CleanupSettings())
        }
        override fun previewCleanup(policy: CleanupPolicy) {}
        override fun pendingReminders(onFailure: () -> Unit, onReady: (Long) -> Unit) { onReady(0) }
        override fun eventLog(onFailure: () -> Unit, onComplete: (List<RuntimeEvent>) -> Unit) { onComplete(emptyList()) }
    }

    private fun mount(actions: RuntimeActions, scale: Float) {
        rule.activity.setContent {
            BeaconTheme(selectedStyle.value, selectedMode.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    Column(Modifier.width(320.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.background).testTag("ui-v2-page")) {
                        BeaconText(stringResource(current.value.titleRes), Modifier.padding(16.dp),
                            style = MaterialTheme.typography.headlineSmall)
                        key(current.value) {
                            SettingsPage(
                                screen = current.value,
                                scroll = rememberLazyListState(), modifier = Modifier.weight(1f),
                                padding = PaddingValues(16.dp),
                                runtimeState = RuntimeState(
                                    configuredEndpoint = "https://example.invalid", runtimeConnected = true,
                                    connectionSummaryLoaded = true, receivePlanLoaded = true,
                                    serverReachable = true, serverCheckResult = "HTTP / WebSocket",
                                    cleanupPolicy = CleanupPolicy(),
                                    cleanupPreview = CleanupPreview("synthetic", 29, 3, 0, 180224, 0, 3, 0),
                                ),
                                actions = actions, connectionForm = remember { ConnectionFormState() },
                                connectionDirty = false, notificationPermission = NotificationPermissionState.ALLOWED,
                                notificationHint = null, uiStyle = selectedStyle.value, themeMode = selectedMode.value,
                                onNavigate = { current.value = it }, receivePlan = ReceivePlan(),
                                receptionForm = remember { ReceptionFormState() }, onSelectReceiveMode = {},
                                onUiStyleChange = { selectedStyle.value = it }, onThemeModeChange = { selectedMode.value = it },
                                onOperationDetails = {}, onFeedback = {},
                                onRequestNotificationPermission = {}, onNotificationSettingsUnavailable = {},
                                updateState = remember { UpdateCheckState() }, onCheckUpdate = {}, quietHoursForm = quiet,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        val bitmap = rule.onNodeWithTag("ui-v2-page").captureToImage().asAndroidBitmap()
        val directory = java.io.File(rule.activity.getExternalFilesDir(null), "ui-v2").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        val command = "cp ${directory.absolutePath}/$name.png /data/local/tmp/beacon-$name.png"
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun matrix(style: BeaconUiStyle, mode: BeaconThemeMode) {
        val repository = RuntimeRepository()
        val actions = actions(repository)
        try {
            for (scale in listOf(1f, 1.3f, 1.5f)) {
                rule.runOnUiThread { selectedStyle.value = style; selectedMode.value = mode; mount(actions, scale) }
                for (screen in SettingsScreen.entries.filterNot { it in setOf(SettingsScreen.SERVER_ADMIN, SettingsScreen.SERVER_DEVICES, SettingsScreen.SERVER_APPLICATIONS, SettingsScreen.SERVER_TEST) }) {
                    rule.runOnIdle { current.value = screen }
                    rule.waitForIdle()
                    rule.onNodeWithTag("ui-v2-page").assertIsDisplayed()
                    capture("$style-$mode-$scale-$screen")
                }
            }
        } finally { rule.runOnUiThread { repository.close() } }
    }

    @Test fun materialLightLayouts() = matrix(BeaconUiStyle.MATERIAL3, BeaconThemeMode.LIGHT)
    @Test fun materialDarkLayouts() = matrix(BeaconUiStyle.MATERIAL3, BeaconThemeMode.DARK)
    @Test fun miuixLightLayouts() = matrix(BeaconUiStyle.MIUIX, BeaconThemeMode.LIGHT)
    @Test fun miuixDarkLayouts() = matrix(BeaconUiStyle.MIUIX, BeaconThemeMode.DARK)

    @Test fun appearanceSelectionAndQuietDraftNavigation() {
        val repository = RuntimeRepository()
        try {
            rule.runOnUiThread { current.value = SettingsScreen.APPEARANCE; mount(actions(repository), 1.5f) }
            rule.onNode(hasText(rule.activity.getString(R.string.appearance_style)) and hasClickAction())
                .performScrollTo().performClick()
            rule.onNode(hasText("MIUIX") and hasClickAction()).performClick()
            rule.runOnIdle { assertEquals(BeaconUiStyle.MIUIX, selectedStyle.value) }
            rule.runOnIdle { current.value = SettingsScreen.NOTIFICATION_RULES }
            rule.runOnIdle { quiet.draft = quiet.draft.copy(enabled = true) }
            rule.onNode(hasText(rule.activity.getString(R.string.quiet_schedule)) and hasClickAction())
                .performScrollTo().performClick()
            rule.runOnIdle {
                assertEquals(SettingsScreen.QUIET_HOURS, current.value)
                quiet.start = "23:15"
                quiet.draft = quiet.draft.copy(enabled = false, weekdays = 31)
                current.value = SettingsScreen.NOTIFICATION_RULES
            }
            rule.waitForIdle()
            rule.runOnIdle {
                assertEquals("23:15", quiet.start)
                assertEquals(31, quiet.draft.weekdays)
                assertFalse(quiet.draft.enabled)
                quiet.save(actions(repository))
                assertEquals(1395, savedQuiet.startMinute)
                assertEquals(31, savedQuiet.weekdays)
            }
        } finally { rule.runOnUiThread { repository.close() } }
    }

    @Test fun connectionEditorCancelsWithoutChangingTheSummary() {
        val repository = RuntimeRepository()
        try {
            rule.runOnUiThread { current.value = SettingsScreen.CONNECTION; mount(actions(repository), 1f) }
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithText(rule.activity.getString(R.string.settings_edit_connection)).performScrollTo().performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(3)
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("https://edited.invalid")
            rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.settings_discard_connection)).assertIsDisplayed()
            rule.onNodeWithText(rule.activity.getString(R.string.action_confirm)).performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithText("https://example.invalid").assertExists()
            rule.onNodeWithText("https://edited.invalid").assertDoesNotExist()
        } finally { rule.runOnUiThread { repository.close() } }
    }

    @Test fun aboutPageShowsVersionProjectLinksAndNotices() {
        val repository = RuntimeRepository()
        try {
            rule.runOnUiThread { current.value = SettingsScreen.ABOUT; mount(actions(repository), 1.5f) }
            rule.onNodeWithText(rule.activity.getString(R.string.settings_version_label, BuildConfig.VERSION_NAME)).assertExists()
            // Licence text moved to the README (ae372ad), so the page keeps only the two links.
            rule.onNode(hasText(rule.activity.getString(R.string.settings_about_project)) and hasClickAction())
                .performScrollTo().assertIsDisplayed()
            rule.onNode(hasText(rule.activity.getString(R.string.ui_v2_docs)) and hasClickAction())
                .performScrollTo().assertIsDisplayed()
            // The notices still ship inside the APK even though no dialog renders them.
            assertTrue(rule.activity.assets.list("third-party").orEmpty().contains("Apache-2.0.txt"))
            val page = rule.onNodeWithTag("ui-v2-page").captureToImage().asAndroidBitmap()
            java.io.File(rule.activity.getExternalFilesDir(null), "about-page.png").outputStream().use {
                page.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { rule.runOnUiThread { repository.close() } }
    }
}
