package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.MessagesPage
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the real page transition backgrounds, rather than an isolated Scaffold. */
class CardSurfaceRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun surfaces(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        val state = RuntimeState(messagesLoaded = true, messageTotal = 1,
            messages = listOf(IncomingMessage("example.test", "surface", "卡片边界检查", "消息内容")))
        try {
            for (mode in listOf(BeaconThemeMode.LIGHT, BeaconThemeMode.DARK)) {
                for (settings in listOf(false, true)) {
                    rule.runOnUiThread { rule.activity.setContent {
                        BeaconTheme(style, mode) {
                            BeaconScaffold(topBar = {}, bottomBar = {}) { insets ->
                                BeaconMessageTransition(updateTransition<IncomingMessage?>(null, label = "message"),
                                    Modifier.fillMaxSize(), animateSize = false) {
                                    if (!settings) MessagesPage(rememberLazyListState(),
                                        Modifier.fillMaxSize().padding(insets), PaddingValues(16.dp), state,
                                        repository, "", MessageReadFilter.ALL, false, emptyList(), "",
                                        onSearch = {}, onApplyFilter = { _, _ -> }, onConnectionSettings = {},
                                        onEnterSelection = {}, onSelectionChange = {}, onSelectionHint = {},
                                        onRequestDelete = {}, onCopyText = { _, _ -> })
                                    else BeaconSettingsTransition(updateTransition(SettingsScreen.HOME, label = "settings"),
                                        animateSize = false) { screen ->
                                        SettingsPage(screen, rememberLazyListState(),
                                            Modifier.fillMaxSize().padding(insets), PaddingValues(16.dp),
                                            state, repository, remember { ConnectionFormState() }, false,
                                            NotificationPermissionState.ALLOWED, null, style, mode, {},
                                            ReceivePlan(), remember { ReceptionFormState() }, {}, {}, {}, {}, {}, {}, {},
                                            remember { UpdateCheckState() }, {})
                                    }
                                }
                            }
                        }
                    } }
                    val pixels = rule.onRoot().captureToImage().toPixelMap()
                    // Card centres include text. Sample just inside the rounded card's left edge.
                    val card = if (settings) rule.onAllNodes(hasText(rule.activity.getString(R.string.ui_v2_server_config_title)),
                        useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
                    else rule.onNodeWithText("卡片边界检查").fetchSemanticsNode().boundsInRoot
                    val density = rule.activity.resources.displayMetrics.density
                    val y = card.center.y.toInt()
                    val gutter = (2 * density).toInt()
                    val inset = (22 * density).toInt()
                    assertNotEquals("$style / $mode / settings=$settings card must contrast with the page",
                        pixels[gutter, y], pixels[inset, y])
                    capture("PAGE_CARDS_${style}_${mode}_${if (settings) "SETTINGS" else "MESSAGES"}")
                }
            }
        } finally { repository.close() }
    }

    private fun metadata(style: BeaconUiStyle) {
        val title = "设备与应用的长名称检查"
        for (scale in listOf(1f, 2f)) {
            rule.runOnUiThread { rule.activity.setContent {
                BeaconTheme(style) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                        Column(Modifier.width(320.dp).padding(16.dp)) {
                            AdminListCard(title, "device-1234567890", "有效", true, emptyList(),
                                Modifier.testTag("admin-card"), priority = 5)
                        }
                    }
                }
            } }
            val menu = rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_more_actions))
                .getUnclippedBoundsInRoot()
            val name = rule.onNodeWithText(title).getUnclippedBoundsInRoot()
            assertTrue("Menu must stay centred on the first name line", kotlin.math.abs((menu.top + menu.bottom).value / 2 -
                (name.top + name.bottom).value / 2) < 2 * scale)
            assertEquals(32.dp, menu.right - menu.left)
            val status = rule.onNodeWithText("有效").getUnclippedBoundsInRoot()
            val priority = rule.onNodeWithText(rule.activity.getString(R.string.settings_priority_normal)).getUnclippedBoundsInRoot()
            assertEquals(status.bottom, priority.bottom)
            assertTrue(status.left > name.left)
            assertTrue(priority.left > status.right)
            capture("ADMIN_TAGS_${style}_$scale")
        }
    }

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = java.io.File(rule.activity.getExternalFilesDir("ui-qa"), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /data/local/tmp/beacon-ui-qa", "cp ${file.absolutePath} /data/local/tmp/beacon-ui-qa/${file.name}")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    @Test fun miuixRealPagesKeepCardSurfaces() = surfaces(BeaconUiStyle.MIUIX)
    @Test fun materialRealPagesKeepCardSurfaces() = surfaces(BeaconUiStyle.MATERIAL3)
    @Test fun miuixAdminMetadataMatchesMessages() = metadata(BeaconUiStyle.MIUIX)
    @Test fun materialAdminMetadataMatchesMessages() = metadata(BeaconUiStyle.MATERIAL3)
}
