package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UiConsistencyRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun shell(style: BeaconUiStyle) {
        for (mode in listOf(BeaconThemeMode.LIGHT, BeaconThemeMode.DARK)) {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style, mode) {
                        val behavior = key(mode) { rememberBeaconBarBehavior() }
                        val listState = key(mode) { rememberLazyListState() }
                        BeaconScaffold(topBar = {
                            Box(Modifier.testTag("toolbar")) {
                                BeaconTopBar("设备管理", false, behavior, {}, {})
                            }
                        }, bottomBar = {}) { padding ->
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)
                                .nestedScroll(behavior.connection(style)).testTag("shell-list"),
                                contentPadding = PaddingValues(16.dp)) {
                                item {
                                    Box(Modifier.testTag("card")) {
                                        BeaconCard(Modifier.fillMaxWidth().height(80.dp),
                                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow)) {}
                                    }
                                }
                                items(30) { BeaconText("设置项目", Modifier.fillMaxWidth().height(70.dp)) }
                            }
                        }
                    }
                }
            }
            val initialBounds = rule.onNodeWithTag("toolbar").getUnclippedBoundsInRoot()
            val initial = initialBounds.bottom - initialBounds.top
            assertTrue("Secondary title must start expanded: $style / $mode / $initial", initial > 100.dp)
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            val card = rule.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
            assertNotEquals("Scaffold background must contrast with cards", pixels[2, pixels.height - 3],
                pixels[card.center.x.toInt(), card.center.y.toInt()])
            capture("SHELL_${style}_${mode}")
            rule.onNodeWithTag("shell-list").performTouchInput { swipeUp() }
            rule.waitForIdle()
            val collapsed = rule.onNodeWithTag("toolbar").getUnclippedBoundsInRoot()
            assertTrue(collapsed.bottom - collapsed.top < initial)
        }
    }

    private fun selection(style: BeaconUiStyle) {
        val selecting = mutableStateOf(false)
        val checked = mutableStateOf(false)
        var calls = 0
        val title = "测试消息标题稳定布局"
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Column(Modifier.width(300.dp).padding(16.dp)) {
                        MessageItem(IncomingMessage("example.com", "test", title, "消息正文"),
                            selecting.value, checked.value, { checked.value = it; calls++ }, {}, {}, false,
                            {}, {}, {}, Modifier.testTag("message-card"))
                    }
                }
            }
        }
        val before = rule.onNodeWithTag("message-card").getUnclippedBoundsInRoot()
        val titleBefore = rule.onNodeWithText(title).getUnclippedBoundsInRoot()
        val more = rule.onNodeWithContentDescription(rule.activity.getString(R.string.ui_message_more_action))
            .getUnclippedBoundsInRoot()
        rule.runOnIdle { selecting.value = true }
        assertEquals(before, rule.onNodeWithTag("message-card").getUnclippedBoundsInRoot())
        assertEquals(titleBefore, rule.onNodeWithText(title).getUnclippedBoundsInRoot())
        val selector = rule.onNode(hasContentDescription(title) and isToggleable())
        assertEquals(more, selector.getUnclippedBoundsInRoot())
        selector.performClick()
        rule.runOnIdle { assertTrue(checked.value); assertEquals(1, calls) }
        capture("SELECTION_${style}")
    }

    private fun filters(style: BeaconUiStyle) {
        val state = AdminListFilterState()
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        AdminStatusFilterAction(state, true, listOf(AdminDevice("d", "测试设备", false)), true)
                        AdminListFilters(state.applicationQuery, { state.applicationQuery = it },
                            state.applicationStatus, { state.applicationStatus = it }, "搜索应用",
                            listOf(AdminDevice("d", "测试设备", false)), state.applicationDevice,
                            { state.applicationDevice = it }, total = 5, active = 3)
                    }
                }
            }
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_filter_title)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.admin_revoked)).performClick()
        rule.runOnIdle { assertEquals(2, state.applicationStatus) }
        rule.onNodeWithText(rule.activity.getString(R.string.admin_stat_revoked, 2)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.admin_revoked)).performClick()
        rule.runOnIdle { assertEquals(0, state.applicationStatus) }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_filter_title)).performClick()
        rule.onNodeWithText("测试设备").performClick()
        rule.runOnIdle { assertEquals("d", state.applicationDevice) }
        rule.onNodeWithText("测试设备").performClick()
        rule.runOnIdle { assertEquals("", state.applicationDevice) }
        rule.onNodeWithText(rule.activity.getString(R.string.msg_clear_filters_action)).assertDoesNotExist()
        capture("FILTERS_${style}")
    }

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage()
        val file = java.io.File(rule.activity.getExternalFilesDir("ui-qa"), "$name.png")
        file.outputStream().use {
            bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /data/local/tmp/beacon-ui-qa", "cp ${file.absolutePath} /data/local/tmp/beacon-ui-qa/${file.name}"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    @Test fun miuixRealScaffoldAndCollapsingTitle() = shell(BeaconUiStyle.MIUIX)
    @Test fun materialRealScaffoldAndCollapsingTitle() = shell(BeaconUiStyle.MATERIAL3)
    @Test fun miuixSelectionKeepsCardGeometry() = selection(BeaconUiStyle.MIUIX)
    @Test fun materialSelectionKeepsCardGeometry() = selection(BeaconUiStyle.MATERIAL3)
    @Test fun miuixAdminFilterMenuAndChips() = filters(BeaconUiStyle.MIUIX)
    @Test fun materialAdminFilterMenuAndChips() = filters(BeaconUiStyle.MATERIAL3)
}
