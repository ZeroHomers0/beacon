package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.*
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeFilterStabilityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        val rows = (0..99).map { IncomingMessage("filter", "$it", "合成消息 $it", "测试正文", receivedAtMs = 1792900000000) }
        val state = mutableStateOf(RuntimeState(messages = rows, messagesLoaded = true,
            runtimeConnected = true, messageTotal = 100, messageUnread = 2))
        var requested by mutableStateOf(MessageReadFilter.ALL)
        var background = Color.Unspecified
        val actions = object : RuntimeActions by repository {
            override fun readMessages(injectTest:Boolean, delete:IncomingMessage?, search:String, serverId:String,
                readFilter:MessageReadFilter, showStatus:Boolean) {}
        }
        try {
            rule.runOnUiThread { rule.activity.setContent {
                BeaconTheme(style, BeaconThemeMode.LIGHT) {
                    background = androidx.compose.material3.MaterialTheme.colorScheme.background
                    val scroll = rememberLazyListState()
                    MessagesPage(scroll, Modifier.fillMaxSize().background(background).safeDrawingPadding(),
                        PaddingValues(BeaconUiMetrics.PageInset), state.value, actions, "", requested, false, emptyList(), "",
                        onSearch = {}, onApplyFilter = { _, filter -> requested = filter; state.value = state.value.copy(dataBusy = true) },
                        onConnectionSettings = {}, onEnterSelection = {}, onSelectionChange = {}, onSelectionHint = {},
                        onRequestDelete = {}, onCopyText = { _, _ -> })
                }
            } }
            val expanded = rule.onNodeWithTag(SEARCH_SURFACE_TAG).getUnclippedBoundsInRoot().let { it.bottom-it.top }
            rule.onNodeWithTag(MESSAGE_LIST_TAG).performTouchInput {
                swipe(Offset(5f, height*.85f), Offset(5f, height*.15f), durationMillis = 400)
            }
            rule.onNodeWithTag(SEARCH_BOX_TAG).assertIsDisplayed()
                assertEquals(expanded.value, rule.onNodeWithTag(SEARCH_SURFACE_TAG).getUnclippedBoundsInRoot().let { (it.bottom-it.top).value }, 1f)
            for (count in listOf(2, 0)) {
                rule.onNodeWithText(rule.activity.getString(R.string.msg_stat_unread, 2L)).performClick()
                rule.runOnIdle { assertEquals(MessageReadFilter.UNREAD, requested) }
                rule.onNodeWithTag(SEARCH_BOX_TAG).assertIsDisplayed()
                assertEquals(expanded.value, rule.onNodeWithTag(SEARCH_SURFACE_TAG).getUnclippedBoundsInRoot().let { (it.bottom-it.top).value }, 1f)
                val image = rule.onNodeWithTag("message-pinned-header").captureToImage().toPixelMap()
                val scale = rule.activity.resources.displayMetrics.density
                for (x in 20..120 step 10) {
                    val pixel = image[(x*scale).toInt(), (2*scale).toInt()]
                    assertEquals("Loading header must stay opaque", background.red, pixel.red, .01f)
                    assertEquals(background.green, pixel.green, .01f)
                    assertEquals(background.blue, pixel.blue, .01f)
                }
                rule.runOnIdle { state.value = state.value.copy(messages = rows.take(count), dataBusy = false,
                    messageQuery = state.value.messageQuery.copy(readFilter = MessageReadFilter.UNREAD)) }
                rule.onNodeWithTag(SEARCH_BOX_TAG).assertIsDisplayed()
                assertEquals(expanded.value, rule.onNodeWithTag(SEARCH_SURFACE_TAG).getUnclippedBoundsInRoot().let { (it.bottom-it.top).value }, 1f)
                if (count > 0) {
                    rule.onNodeWithText(rule.activity.getString(R.string.msg_stat_total, 100L)).performClick()
                    rule.runOnIdle { state.value = state.value.copy(messages = rows, dataBusy = false,
                        messageQuery = state.value.messageQuery.copy(readFilter = MessageReadFilter.ALL)) }
                    rule.onNodeWithTag(SEARCH_BOX_TAG).assertIsDisplayed()
                assertEquals(expanded.value, rule.onNodeWithTag(SEARCH_SURFACE_TAG).getUnclippedBoundsInRoot().let { (it.bottom-it.top).value }, 1f)
                }
            }
            rule.onNodeWithTag(MESSAGE_LIST_TAG).performTouchInput {
                swipe(Offset(5f, height*.2f), Offset(5f, height*.85f), durationMillis = 500)
            }
            rule.onNodeWithTag(SEARCH_BOX_TAG).assertExists()
            val restored = rule.onNodeWithTag(SEARCH_SURFACE_TAG).getUnclippedBoundsInRoot().let { it.bottom-it.top }
            assertEquals(expanded.value, restored.value, 1f)
        } finally { repository.close() }
    }
    @Test fun miuixKeepsFixedSearchAcrossShortAndEmptyResults() = verify(BeaconUiStyle.MIUIX)
    @Test fun materialKeepsFixedSearchAcrossShortAndEmptyResults() = verify(BeaconUiStyle.MATERIAL3)
}
