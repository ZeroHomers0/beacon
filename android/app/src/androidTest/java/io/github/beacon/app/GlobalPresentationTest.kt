package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.MessageDetailScreen
import io.github.beacon.app.feature.messages.MessagesPage
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconTimePreference
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlobalPresentationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        val bitmap = rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val output = java.io.File(rule.activity.getExternalFilesDir(null), "$name.png")
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val copy = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp ${output.absolutePath} /data/local/tmp/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
    }

    private fun emptyStates(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        var configured = 0
        var synced = 0
        var cleared = 0
        val actions = object : RuntimeActions by repository {
            override fun transport(settings: TransportSettings?, notifyOnly: Boolean, onConfigured: (() -> Unit)?) {
                synced++
            }
        }
        try {
            for (kind in 0..2) {
                rule.runOnUiThread {
                    rule.activity.setContent {
                        BeaconTheme(style) {
                            MessagesPage(
                                rememberLazyListState(), Modifier.fillMaxSize(), PaddingValues(16.dp),
                                RuntimeState(messagesLoaded = true, configuredEndpoint = if (kind == 0) "" else "https://example.com"),
                                actions, if (kind == 2) "无匹配关键词" else "", MessageReadFilter.ALL,
                                false, emptyList(), "", onSearch = {},
                                onApplyFilter = { query, filter ->
                                    assertEquals("", query)
                                    assertEquals(MessageReadFilter.ALL, filter)
                                    cleared++
                                },
                                onConnectionSettings = { configured++ },
                                onEnterSelection = {}, onSelectionChange = {}, onSelectionHint = {}, onRequestDelete = {}, onCopyText = { _, _ -> },
                            )
                        }
                    }
                }
                val title = when (kind) {
                    0 -> R.string.msg_empty_unconfigured
                    1 -> R.string.msg_empty_no_messages
                    else -> R.string.msg_empty_no_match
                }
                rule.onNodeWithText(rule.activity.getString(title)).assertIsDisplayed()
                val action = when (kind) {
                    0 -> R.string.settings_connection_section_title
                    1 -> R.string.action_sync_now
                    else -> R.string.msg_clear_filters_action
                }
                rule.onAllNodesWithText(rule.activity.getString(action)).onLast().performClick()
            }
            capture("global-empty-$style")
            assertEquals(1, configured)
            assertEquals(1, synced)
            assertEquals(1, cleared)
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    private fun details(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    MessageDetailScreen(
                        IncomingMessage("synthetic-server", "synthetic-id", "正文优先", "消息正文",
                            applicationId = "a".repeat(64), applicationName = "回归应用", group = "回归分组"),
                        Modifier.fillMaxSize(), {}, {}, false, {},
                    )
                }
            }
        }
        rule.onNodeWithText("回归应用").assertIsDisplayed()
        rule.onNodeWithText("回归分组").assertIsDisplayed()
        rule.onNodeWithText("消息 ID：synthetic-id").assertDoesNotExist()
        capture("global-detail-$style")
        rule.onNodeWithText(rule.activity.getString(R.string.msg_detail_expand_raw_action)).performScrollTo().performClick()
        rule.onNodeWithText("消息 ID：synthetic-id").assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.msg_detail_received_time_label)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.msg_detail_collapse_raw_action)).performScrollTo().performClick()
        rule.onNodeWithText("消息 ID：synthetic-id").assertDoesNotExist()
    }

    private fun largeFont(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    BeaconTheme(style) {
                        Column(Modifier.width(320.dp)) {
                            BeaconTimePreference("22:00", {}, "开始时间")
                        }
                    }
                }
            }
        }
        rule.onNode(hasContentDescription("时 22") and isSelected()).assertIsDisplayed()
        rule.onNode(hasContentDescription("分 00") and isSelected()).assertIsDisplayed()
        val wheel = rule.onNodeWithTag("开始时间-hour-wheel").getUnclippedBoundsInRoot()
        assertTrue("The wheel grows for system font scale", (wheel.bottom - wheel.top).value > 96f)
        capture("global-largefont-$style")
    }

    @Test fun emptyStatesMaterial() = emptyStates(BeaconUiStyle.MATERIAL3)
    @Test fun emptyStatesMiuix() = emptyStates(BeaconUiStyle.MIUIX)
    @Test fun detailsMaterial() = details(BeaconUiStyle.MATERIAL3)
    @Test fun detailsMiuix() = details(BeaconUiStyle.MIUIX)
    @Test fun largeFontMaterial() = largeFont(BeaconUiStyle.MATERIAL3)
    @Test fun largeFontMiuix() = largeFont(BeaconUiStyle.MIUIX)
}
