package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

class GlassAndButtonAppearanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun glass(mode: BeaconThemeMode) {
        lateinit var state: BeaconGlassState
        rule.runOnUiThread { rule.activity.setContent {
            BeaconTheme(BeaconUiStyle.MIUIX, mode) {
                state = rememberBeaconGlassState()
                val backdrop = rememberLayerBackdrop()
                CompositionLocalProvider(LocalBeaconGlass provides state, LocalBeaconGlassBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
                            .background(Brush.linearGradient(listOf(Color(0xFF246FC0), Color(0xFFE9A475)))))
                        BeaconGlassSurface(MaterialTheme.colorScheme.surface,
                            Modifier.align(Alignment.Center).size(260.dp, 110.dp).testTag("glass")) {
                            BeaconText("柔光玻璃", Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        } }
        rule.onNodeWithText("柔光玻璃").assertIsDisplayed()
        rule.runOnIdle { assertTrue("Hardware renderer must enable glass in this fixture", state.available) }
        val bounds = rule.onNodeWithTag("glass").getUnclippedBoundsInRoot()
        val optical = rule.onNodeWithTag("glass").captureToImage().asAndroidBitmap()
        capture("glass-$mode")
        rule.runOnIdle { state.disable() }
        rule.onNodeWithText("柔光玻璃").assertIsDisplayed()
        assertEquals(bounds, rule.onNodeWithTag("glass").getUnclippedBoundsInRoot())
        val fallback = rule.onNodeWithTag("glass").captureToImage().asAndroidBitmap()
        assertNotEquals(optical.getPixel(optical.width / 2, 8), fallback.getPixel(fallback.width / 2, 8))
        capture("fallback-$mode")
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

    @Test fun glassAndFallbackLight() = glass(BeaconThemeMode.LIGHT)
    @Test fun glassAndFallbackDark() = glass(BeaconThemeMode.DARK)
    @Test fun miuixSystemButtonPalette() = buttons(BeaconUiStyle.MIUIX)
    @Test fun materialButtonPalette() = buttons(BeaconUiStyle.MATERIAL3)
}
