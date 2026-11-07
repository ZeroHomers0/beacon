package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpRect
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

private val DpRect.width get() = right - left
private val DpRect.height get() = bottom - top

class ScrollPresentationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun search(style: BeaconUiStyle) {
        for (mode in listOf(BeaconThemeMode.LIGHT, BeaconThemeMode.DARK)) for (font in listOf(1f, 2f)) {
            lateinit var scroll: LazyListState
            lateinit var scope: CoroutineScope
            lateinit var focus: androidx.compose.ui.focus.FocusManager
            var query by mutableStateOf("")
            var submitted = 0
            var half = 0
            rule.runOnUiThread { rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                    BeaconTheme(style, mode) {
                        scroll = rememberLazyListState(); scope = rememberCoroutineScope()
                        focus = LocalFocusManager.current
                        half = with(LocalDensity.current) { (BeaconUiMetrics.SearchCollapseDistance / 2).roundToPx() }
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                            BeaconSearchBox(query, { query = it }, "搜索测试", scroll = scroll, onSearch = { submitted++ })
                            LazyColumn(state = scroll, modifier = Modifier.weight(1f).testTag("search-list")) {
                                items(20) { BeaconText("列表项目 $it", Modifier.fillMaxWidth().height(240.dp)) }
                            }
                        }
                    }
                }
            } }
            val full = rule.onNodeWithTag(SEARCH_COLLAPSE_TAG).getUnclippedBoundsInRoot()
            rule.runOnIdle { scope.launch { scroll.scrollToItem(0, half) } }
            val partial = rule.onNodeWithTag(SEARCH_COLLAPSE_TAG).getUnclippedBoundsInRoot()
            assertEquals(full.width.value, partial.width.value, 1f)
            assertEquals(full.height.value / 2, partial.height.value, 1f)
            capture("search-half-$style-$mode-$font")
            rule.onNodeWithTag(SEARCH_BOX_TAG).performTextReplacement("保留查询")
            assertEquals(full.width.value, rule.onNodeWithTag(SEARCH_COLLAPSE_TAG).getUnclippedBoundsInRoot().width.value, 1f)
            rule.runOnIdle { focus.clearFocus(); scope.launch { scroll.scrollToItem(1) } }
            rule.onNodeWithTag(SEARCH_BOX_TAG).assertDoesNotExist()
            rule.runOnIdle { assertEquals("保留查询", query); assertEquals(0, submitted) }
            capture("search-hidden-$style-$mode-$font")
            rule.runOnIdle { scope.launch { scroll.scrollToItem(0) } }
            rule.onNodeWithTag(SEARCH_BOX_TAG).assertExists()
            rule.onNodeWithText("保留查询").assertExists()
            assertEquals(full.width.value, rule.onNodeWithTag(SEARCH_COLLAPSE_TAG).getUnclippedBoundsInRoot().width.value, 1f)
        }
    }

    private fun switch(style: BeaconUiStyle) {
        for (mode in listOf(BeaconThemeMode.LIGHT, BeaconThemeMode.DARK)) for (font in listOf(1f, 2f)) {
            val selected = mutableIntStateOf(0)
            val enabled = mutableStateOf(true)
            var calls = 0
            rule.runOnUiThread { rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                    BeaconTheme(style, mode) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth().testTag("old-switch")) {
                                BeaconSummaryPill("全部 20", BeaconIcons.Mail, modifier = Modifier.weight(1f), compact = true, onClick = {})
                                BeaconSummaryPill("未读 12", BeaconIcons.Messages, modifier = Modifier.weight(1f), compact = true, onClick = {})
                            }
                            Spacer(Modifier.height(16.dp))
                            Box(Modifier.testTag("new-switch")) {
                                BeaconListSwitch(listOf("全部 20", "未读 12"), listOf(BeaconIcons.Mail, BeaconIcons.Messages),
                                    selected.intValue, enabled.value, onSelected = { selected.intValue = it; calls++ })
                            }
                            Box(Modifier.fillMaxWidth().height(80.dp).beaconFilterChange(selected.intValue)) {
                                BeaconText("结果 ${selected.intValue}")
                            }
                        }
                    }
                }
            } }
            val initial = rule.onNodeWithTag("new-switch").getUnclippedBoundsInRoot()
            assertEquals(rule.onNodeWithTag("old-switch").getUnclippedBoundsInRoot().height.value, initial.height.value, .5f)
            val second = rule.onNode(hasText("未读 12") and hasAnyAncestor(hasTestTag("new-switch")))
            rule.mainClock.autoAdvance = false
            second.performClick()
            rule.mainClock.advanceTimeBy(100)
            capture("switch-moving-$style-$mode-$font")
            rule.mainClock.advanceTimeBy(2000)
            rule.mainClock.autoAdvance = true
            second.assertIsSelected()
            rule.onNodeWithText("结果 1").assertIsDisplayed()
            assertEquals(initial.height, rule.onNodeWithTag("new-switch").getUnclippedBoundsInRoot().height)
            rule.runOnIdle { assertEquals(1, calls); enabled.value = false }
            second.assertIsNotEnabled().performTouchInput { click() }
            rule.runOnIdle { assertEquals(1, calls) }
            capture("switch-settled-$style-$mode-$font")
        }
    }

    private fun header(mode: BeaconThemeMode) {
        val progress = mutableFloatStateOf(0f)
        var clicks = 0
        rule.runOnUiThread { rule.activity.setContent {
            BeaconTheme(BeaconUiStyle.MIUIX, mode) {
                val behavior = rememberBeaconBarBehavior()
                CompositionLocalProvider(LocalBeaconScrollProgress provides { progress.floatValue }) {
                    BeaconScaffold(topBar = {
                        BeaconTopBar("滚动顶栏", true, behavior,
                            navigationIcon = { BeaconIconButton({ clicks++ }, Modifier.testTag("back-control")) {
                                androidx.compose.material3.Icon(BeaconIcons.Back, "返回测试")
                            } }, actions = { BeaconActionIcon(BeaconIcons.Sync, "刷新测试", onClick = { clicks++ }) })
                    }, bottomBar = {}) { padding ->
                        Box(Modifier.fillMaxSize().background(Color(0xFFA8C9E5)).padding(padding))
                    }
                }
            }
        } }
        val back = rule.onNodeWithTag("back-control")
        val before = back.captureToImage().asAndroidBitmap()
        val bounds = back.getUnclippedBoundsInRoot()
        capture("header-clear-$mode")
        rule.runOnIdle { progress.floatValue = 1f }
        val after = back.captureToImage().asAndroidBitmap()
        assertNotEquals(before.getPixel(before.width / 8, before.height / 2), after.getPixel(after.width / 8, after.height / 2))
        assertEquals(bounds, back.getUnclippedBoundsInRoot())
        back.performClick()
        rule.onNodeWithContentDescription("刷新测试").performClick()
        rule.runOnIdle { assertEquals(2, clicks) }
        capture("header-glass-$mode")
        rule.runOnIdle { progress.floatValue = 0f }
        rule.mainClock.advanceTimeBy(1000)
        val restored = back.captureToImage().asAndroidBitmap()
        assertEquals(before.getPixel(before.width / 8, before.height / 2), restored.getPixel(restored.width / 8, restored.height / 2))
    }

    private fun capture(name: String) {
        val file = java.io.File(rule.activity.getExternalFilesDir("scroll-qa"), "$name.png")
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /data/local/tmp/beacon-scroll-qa", "cp ${file.absolutePath} /data/local/tmp/beacon-scroll-qa/${file.name}"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Test fun materialSearchCollapsesAndKeepsQuery() = search(BeaconUiStyle.MATERIAL3)
    @Test fun miuixSearchCollapsesAndKeepsQuery() = search(BeaconUiStyle.MIUIX)
    @Test fun materialSwitchHeightAndSelection() = switch(BeaconUiStyle.MATERIAL3)
    @Test fun miuixSwitchHeightAndSelection() = switch(BeaconUiStyle.MIUIX)
    @Test fun miuixHeaderRevealsLight() = header(BeaconThemeMode.LIGHT)
    @Test fun miuixHeaderRevealsDark() = header(BeaconThemeMode.DARK)
}
