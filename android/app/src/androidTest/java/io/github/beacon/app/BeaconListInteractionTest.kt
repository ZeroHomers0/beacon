@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.feature.messages.MessageDetailScreen
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BeaconListInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val message =
        IncomingMessage(
            "synthetic-source",
            "not-a-number-id",
            "标题回归",
            "正文回归",
            1791504062000L,
            1791504063000L,
            true,
            "副标题回归",
        )

    private fun detail(style: BeaconUiStyle) {
        val copied = AtomicInteger()
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    MessageDetailScreen(
                        message,
                        Modifier.fillMaxSize(),
                        onCopy = { if (it) copied.incrementAndGet() },
                        onDelete = {},
                        busy = false,
                        onToggleRead = {},
                    )
                }
            }
        }
        rule.onNodeWithText("查看消息信息").performScrollTo().performClick()
        rule.onNodeWithText("消息 ID：not-a-number-id").assertExists()
        rule.onNodeWithText("查看来源消息").assertDoesNotExist()
        rule.onNodeWithText("复制正文").assertDoesNotExist()
        rule.onNodeWithContentDescription("详情更多操作").performScrollTo().performClick()
        rule.onNodeWithText("复制正文").performClick()
        rule.waitUntil { copied.get() == 1 }
    }

    @Test fun rawInformationAndDetailsMenuMiuix() = detail(BeaconUiStyle.MIUIX)

    @Test fun rawInformationAndDetailsMenuMaterial() = detail(BeaconUiStyle.MATERIAL3)

    private fun card(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    var selected by remember { mutableStateOf(false) }
                    MessageItem(
                        message,
                        selected,
                        selected,
                        { selected = it },
                        {},
                        {},
                        false,
                        {},
                        {},
                        onLongPress = { selected = true },
                    )
                }
            }
        }
        val title = rule.onNodeWithText("标题回归", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val subtitle =
            rule.onNodeWithText("副标题回归", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val body = rule.onNodeWithText("正文回归", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(title.top < subtitle.top && subtitle.top < body.top)
        rule.onNodeWithText("标题回归", useUnmergedTree = true).performTouchInput { longClick() }
        rule.onNode(isToggleable()).assertIsOn()
    }

    @Test fun titleFirstAndLongPressMiuix() = card(BeaconUiStyle.MIUIX)

    @Test fun titleFirstAndLongPressMaterial() = card(BeaconUiStyle.MATERIAL3)

    private fun pull(style: BeaconUiStyle) {
        val refreshes = AtomicInteger()
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    BeaconPullToRefresh(
                        false,
                        { refreshes.incrementAndGet() },
                        Modifier.fillMaxSize().testTag("refresh"),
                    ) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(30) { BeaconText("合成 $it", Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("refresh").performTouchInput {
            swipeDown(startY = height * .15f, endY = height * .8f, durationMillis = 700)
        }
        rule.waitUntil(5000) { refreshes.get() == 1 }
    }

    @Test fun pullToRefreshMiuix() = pull(BeaconUiStyle.MIUIX)

    @Test fun pullToRefreshMaterial() = pull(BeaconUiStyle.MATERIAL3)

    private fun expandTitleBeforeRefresh(style: BeaconUiStyle) {
        val refreshes = AtomicInteger()
        lateinit var behavior: BeaconBarBehavior
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    behavior = rememberBeaconBarBehavior()
                    Column {
                        BeaconTopBar("消息", false, behavior, {}, {})
                        BeaconPullToRefresh(
                            false,
                            { refreshes.incrementAndGet() },
                            Modifier.weight(1f).testTag("expand-before-refresh"),
                            indicatorPadding = PaddingValues(top = 64.dp),
                        ) {
                            LazyColumn(
                                Modifier.fillMaxSize().nestedScroll(behavior.connection(style))
                            ) {
                                items(30) { BeaconText("合成 $it", Modifier.padding(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
        rule.runOnIdle {
            if (style == BeaconUiStyle.MIUIX)
                behavior.miuix.state.heightOffset = behavior.miuix.state.heightOffsetLimit
            else behavior.material.state.heightOffset = behavior.material.state.heightOffsetLimit
        }
        rule.onNodeWithTag("expand-before-refresh").performTouchInput {
            // Inspect while dragging: releasing a short drag may legitimately snap back.
            down(androidx.compose.ui.geometry.Offset(centerX, height * .2f))
            moveTo(androidx.compose.ui.geometry.Offset(centerX, height * .2f + 30.dp.toPx()), 700)
        }
        rule.runOnIdle {
            assertEquals(0, refreshes.get())
            val offset =
                if (style == BeaconUiStyle.MIUIX) behavior.miuix.state.heightOffset
                else behavior.material.state.heightOffset
            val limit =
                if (style == BeaconUiStyle.MIUIX) behavior.miuix.state.heightOffsetLimit
                else behavior.material.state.heightOffsetLimit
            assertTrue("Downward drag must expand the collapsed title", offset > limit)
        }
        rule.onNodeWithTag("expand-before-refresh").performTouchInput { up() }
        rule.runOnIdle {
            if (style == BeaconUiStyle.MIUIX) behavior.miuix.state.heightOffset = 0f
            else behavior.material.state.heightOffset = 0f
        }
        rule.onNodeWithTag("expand-before-refresh").performTouchInput {
            swipeDown(startY = height * .15f, endY = height * .8f, durationMillis = 700)
        }
        rule.waitUntil(5000) { refreshes.get() == 1 }
    }

    @Test
    fun collapsedTitleExpandsBeforeRefreshMiuix() = expandTitleBeforeRefresh(BeaconUiStyle.MIUIX)

    @Test
    fun collapsedTitleExpandsBeforeRefreshMaterial() =
        expandTitleBeforeRefresh(BeaconUiStyle.MATERIAL3)

    @Test
    fun serverWarningReplacesModeAndRemainsClickable() {
        val opened = AtomicInteger()
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(BeaconUiStyle.MIUIX) {
                    ReceiveModeButton(
                        true,
                        io.github.beacon.app.domain.model.ReceiveMode.POWER_SAVING,
                        { opened.incrementAndGet() },
                        {},
                        serverIssue = true,
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("服务器未配置或连接异常，打开服务器配置").performClick()
        rule.runOnIdle { assertEquals(1, opened.get()) }
        rule.onNodeWithContentDescription("接收模式 · 省电接收 · 接收已暂停").assertDoesNotExist()
    }

    private fun preference(style: BeaconUiStyle) {
        val result = AtomicInteger(-1)
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    BeaconDropdownPreference("界面风格", listOf("方案甲", "方案乙"), 0) { result.set(it) }
                }
            }
        }
        rule.onNodeWithText("界面风格").performClick()
        rule.onNodeWithText("方案乙").performClick()
        rule.waitUntil { result.get() == 1 }
    }

    @Test fun fullPreferenceRowMiuix() = preference(BeaconUiStyle.MIUIX)

    @Test fun fullPreferenceRowMaterial() = preference(BeaconUiStyle.MATERIAL3)

    private fun receiveModes(style: BeaconUiStyle) {
        val selected = AtomicInteger()
        val settings = AtomicInteger()
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    var open by remember { mutableStateOf(false) }
                    io.github.beacon.app.ui.components.ReceiveModeButton(
                        false,
                        io.github.beacon.app.domain.model.ReceiveMode.POWER_SAVING,
                        onClick = { open = true },
                        onLongClick = { settings.incrementAndGet() },
                    )
                    if (open)
                        io.github.beacon.app.feature.settings.ReceiveModeOptions(
                            io.github.beacon.app.domain.model.ReceiveMode.POWER_SAVING,
                            enabled = true,
                            onSelect = { selected.set(it.ordinal) },
                        )
                }
            }
        }
        rule.onNodeWithContentDescription("接收模式 · 省电接收 · 接收已暂停").performClick()
        rule.onNodeWithText("即时接收").assertExists().performClick()
        rule.waitUntil {
            selected.get() == io.github.beacon.app.domain.model.ReceiveMode.REALTIME.ordinal
        }
        rule.onNodeWithContentDescription("接收模式 · 省电接收 · 接收已暂停").performClick()
        rule.onNodeWithText("规则接收").performClick()
        rule.waitUntil {
            selected.get() == io.github.beacon.app.domain.model.ReceiveMode.RULES.ordinal
        }
        rule.onNodeWithContentDescription("接收模式 · 省电接收 · 接收已暂停").performTouchInput { longClick() }
        rule.waitUntil { settings.get() == 1 }
    }

    @Test fun receptionModeSelectionAndLongPressMiuix() = receiveModes(BeaconUiStyle.MIUIX)

    @Test fun receptionModeSelectionAndLongPressMaterial() = receiveModes(BeaconUiStyle.MATERIAL3)
}
