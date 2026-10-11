package io.github.beacon.app

import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.BeaconScaffold
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextField
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SdkSuppress(minSdkVersion = 30)
@RunWith(AndroidJUnit4::class)
class KeyboardOverlayTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Box(Modifier.fillMaxSize().testTag("keyboard-viewport")) {
                        BeaconScaffold(
                            topBar = {},
                            bottomBar = {
                                Box(
                                    Modifier.fillMaxWidth()
                                        .height(80.dp)
                                        .testTag("fixed-navigation")
                                ) {
                                    BeaconText("固定底栏")
                                }
                            },
                        ) { padding ->
                            var text by remember { mutableStateOf("") }
                            Column(
                                Modifier.fillMaxSize().padding(padding).testTag("keyboard-page")
                            ) {
                                BeaconTextField(
                                    text,
                                    { text = it },
                                    Modifier.fillMaxWidth().testTag("keyboard-field"),
                                    label = { BeaconText("服务器地址") },
                                    labelText = "服务器地址",
                                )
                            }
                        }
                    }
                }
            }
        }
        val viewport = rule.onNodeWithTag("keyboard-viewport").getUnclippedBoundsInRoot()
        val page = rule.onNodeWithTag("keyboard-page").getUnclippedBoundsInRoot()
        val navigation = rule.onNodeWithTag("fixed-navigation").getUnclippedBoundsInRoot()
        rule.onNodeWithTag("keyboard-field").performClick()
        rule.waitUntil(5000) {
            rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ==
                true
        }
        assertEquals(viewport, rule.onNodeWithTag("keyboard-viewport").getUnclippedBoundsInRoot())
        assertEquals(page, rule.onNodeWithTag("keyboard-page").getUnclippedBoundsInRoot())
        assertEquals(navigation, rule.onNodeWithTag("fixed-navigation").getUnclippedBoundsInRoot())
        Espresso.closeSoftKeyboard()
        rule.waitUntil(5000) {
            rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ==
                false
        }
        assertEquals(navigation, rule.onNodeWithTag("fixed-navigation").getUnclippedBoundsInRoot())
        rule.onNodeWithText("固定底栏").assertIsDisplayed()
    }

    @Test fun keyboardOverlaysWithoutResizingMiuix() = verify(BeaconUiStyle.MIUIX)

    @Test fun keyboardOverlaysWithoutResizingMaterial() = verify(BeaconUiStyle.MATERIAL3)

    private fun verifyLowerField(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Column(
                        Modifier.fillMaxSize()
                            .imePadding()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    ) {
                        Spacer(Modifier.height(700.dp))
                        var value by remember { mutableStateOf("") }
                        BeaconTextField(
                            value,
                            { value = it },
                            Modifier.fillMaxWidth().testTag("lower-field"),
                            label = { BeaconText("设备名称") },
                            labelText = "设备名称",
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
        rule.onNodeWithTag("lower-field").performScrollTo().performClick()
        rule.waitUntil(5000) {
            rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ==
                true
        }
        rule.waitUntil(5000) {
            val view = rule.activity.window.decorView
            val keyboardTop =
                view.height - view.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
            rule.onNodeWithTag("lower-field").getUnclippedBoundsInRoot().bottom.value *
                view.resources.displayMetrics.density <= keyboardTop + 2
        }
        Espresso.closeSoftKeyboard()
    }

    @Test fun lowerMiuixFieldScrollsAboveKeyboard() = verifyLowerField(BeaconUiStyle.MIUIX)

    @Test fun lowerMaterialFieldScrollsAboveKeyboard() = verifyLowerField(BeaconUiStyle.MATERIAL3)
}
