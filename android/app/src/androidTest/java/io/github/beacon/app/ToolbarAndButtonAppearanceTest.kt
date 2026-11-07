package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ToolbarAndButtonAppearanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun opaqueHeader(mode: BeaconThemeMode) {
        var clicks = 0
        var longClicks = 0
        rule.runOnUiThread { rule.activity.setContent {
            BeaconTheme(BeaconUiStyle.MIUIX, mode) {
                    Box(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val stripe = 12.dp.toPx()
                            for (x in 0..(size.width / stripe).toInt()) {
                                drawRect(if (x % 2 == 0) Color(0xFF2567AC) else Color(0xFFF7C978),
                                    topLeft = androidx.compose.ui.geometry.Offset(x * stripe, 0f),
                                    size = androidx.compose.ui.geometry.Size(stripe, size.height))
                            }
                        }
                        BeaconSurface(MaterialTheme.colorScheme.background,
                            Modifier.fillMaxWidth().height(180.dp).testTag("opaque-header")) {
                            Box(Modifier.align(Alignment.CenterStart)) {
                                ReceiveModeButton(false, io.github.beacon.app.domain.model.ReceiveMode.POWER_SAVING,
                                    { clicks++ }, { longClicks++ }, visible = false)
                            }
                            BeaconText("顶部背景", Modifier.align(Alignment.Center))
                        }
                    }
            }
        } }
        rule.onNodeWithText("顶部背景").assertIsDisplayed()
        val image = rule.onNodeWithTag("opaque-header").captureToImage().asAndroidBitmap()
        fun rowContrast(y: Int): Int {
            val values = (5 until image.width / 3).map { x -> android.graphics.Color.blue(image.getPixel(x, y)) }
            return values.max() - values.min()
        }
        assertEquals("The opaque header must not show background stripes", 0,
            rowContrast((image.height * .95f).toInt()))
        capture("opaque-header-$mode")
        val button = rule.onNodeWithContentDescription("接收模式 · 省电接收 · 接收已暂停")
        button.performClick()
        button.performTouchInput { longClick() }
        rule.runOnIdle { assertEquals(1, clicks); assertEquals(1, longClicks) }
        rule.onNodeWithText("顶部背景").assertIsDisplayed()
        capture("opaque-header-fallback-$mode")
    }

    private fun buttons(style: BeaconUiStyle) {
        for (mode in listOf(BeaconThemeMode.LIGHT, BeaconThemeMode.DARK)) {
            var clicks = 0
            var primary = Color.Unspecified
            var secondary = Color.Unspecified
            var expectedDangerText = Color.Unspecified
            var dangerText = Color.Unspecified
            rule.runOnUiThread { rule.activity.setContent {
                BeaconTheme(style, mode) {
                    primary = MaterialTheme.colorScheme.primary
                    secondary = if (style == BeaconUiStyle.MIUIX)
                        top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.secondaryVariant
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                    expectedDangerText = if (style == BeaconUiStyle.MIUIX)
                        top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onError
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BeaconButton({ clicks++ }, Modifier.fillMaxWidth().testTag("primary")) { BeaconText("确认") }
                        BeaconTextButton({}, Modifier.fillMaxWidth().testTag("secondary")) { BeaconText("取消") }
                        BeaconButton({}, Modifier.fillMaxWidth(), enabled = false) { BeaconText("不可用") }
                        BeaconDangerButton({}, Modifier.fillMaxWidth()) {
                            dangerText = androidx.compose.material3.LocalContentColor.current
                            BeaconText("删除")
                        }
                    }
                }
            } }
            rule.onNodeWithText("不可用").assertIsNotEnabled()
            fun fill(tag: String) = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap().let {
                Color(it.getPixel(it.width / 2, it.height / 4))
            }
            fun close(expected: Color, actual: Color) {
                assertEquals(expected.red, actual.red, .025f)
                assertEquals(expected.green, actual.green, .025f)
                assertEquals(expected.blue, actual.blue, .025f)
            }
            close(primary, fill("primary"))
            close(secondary, fill("secondary"))
            rule.runOnIdle { assertEquals(expectedDangerText, dangerText) }
            capture("buttons-$style-$mode")
            rule.onNodeWithText("确认").performClick()
            rule.runOnIdle { assertEquals(1, clicks) }
        }
    }

    private fun capture(name: String) {
        val file = java.io.File(rule.activity.getExternalFilesDir("ui-qa"), "$name.png")
        file.outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // Gradle uninstalls the test app afterwards; keep QA images outside its data directory.
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /data/local/tmp/beacon-ui-upgrade",
                "cp ${file.absolutePath} /data/local/tmp/beacon-ui-upgrade/${file.name}")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                .use { it.readBytes() }
        }
    }

    @Test fun miuixSystemButtonPalette() = buttons(BeaconUiStyle.MIUIX)
    @Test fun materialButtonPalette() = buttons(BeaconUiStyle.MATERIAL3)
    @Test fun opaqueHeaderAndModeButtonLight() = opaqueHeader(BeaconThemeMode.LIGHT)
    @Test fun opaqueHeaderAndModeButtonDark() = opaqueHeader(BeaconThemeMode.DARK)
}
