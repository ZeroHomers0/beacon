package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.beacon.app.data.local.PollingWire
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.MessagesPage
import io.github.beacon.app.ui.components.BeaconScaffold
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageRefreshLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun pinnedSearch(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        MessagesPage(
                            scroll = rememberLazyListState(),
                            modifier = Modifier.fillMaxSize(),
                            padding = PaddingValues(16.dp),
                            runtimeState = RuntimeState(),
                            actions = repository,
                            search = "",
                            readFilter = MessageReadFilter.ALL,
                            selectionMode = false,
                            selectedKeys = emptyList(),
                            selectionHint = "",
                            onSearch = {},
                            onApplyFilter = { _, _ -> },
                            onConnectionSettings = {},
                            onEnterSelection = {},
                            onSelectionChange = {},
                            onSelectionHint = {},
                            onCopyText = { _, _ -> },
                        )
                    }
                }
            }
            val search =
                rule.onNodeWithText(
                    rule.activity.getString(R.string.msg_search_hint),
                    useUnmergedTree = true,
                )
            val before = search.getUnclippedBoundsInRoot()
            rule.onRoot().performTouchInput {
                down(Offset(centerX, height * .3f))
                moveTo(Offset(centerX, height * .75f), 600)
            }
            val during = search.getUnclippedBoundsInRoot()
            assertEquals(before.top.value, during.top.value, .5f)
            assertEquals(before.bottom.value, during.bottom.value, .5f)
            if (style == BeaconUiStyle.MIUIX) {
                val indicator =
                    rule
                        .onNodeWithText(
                            rule.activity.getString(R.string.ui_release_to_refresh_hint)
                        )
                        .getUnclippedBoundsInRoot()
                assertTrue(
                    "Refresh indicator must remain below the fixed search field",
                    indicator.top >= during.bottom,
                )
            }
            rule.onRoot().performTouchInput { up() }
        } finally {
            repository.close()
        }
    }

    @Test fun searchStaysOutsidePullContainerMiuix() = pinnedSearch(BeaconUiStyle.MIUIX)

    @Test fun searchStaysOutsidePullContainerMaterial() = pinnedSearch(BeaconUiStyle.MATERIAL3)

    @Test
    fun legacyPersistedResultIsIgnored() {
        val original = PollingState(lastSuccessInserted = 1)
        val legacy = org.json.JSONObject(PollingWire.encode(original)).put("result", "旧接收结果")
        val restored = PollingWire.decode(legacy.toString())
        assertEquals(original, restored)
    }

    @Test
    fun resultSnackbarUsesAvailableWidth() {
        val feedback = "本轮同步：新增 1 条"
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(BeaconUiStyle.MIUIX) {
                    val host = androidx.compose.runtime.remember { SnackbarHostState() }
                    LaunchedEffect(Unit) { host.showSnackbar(feedback) }
                    Box(Modifier.fillMaxSize().testTag("feedback-viewport")) {
                        BeaconScaffold({}, {}, host) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        val message = rule.onNodeWithText(feedback).getUnclippedBoundsInRoot()
        val viewport = rule.onNodeWithTag("feedback-viewport").getUnclippedBoundsInRoot()
        assertTrue(
            "Short feedback must not shrink the result panel",
            (message.right - message.left) > (viewport.right - viewport.left) * .65f,
        )
    }
}
