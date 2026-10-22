package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsActionsLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle, scale: Float) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Column(Modifier.width(280.dp).testTag("actions-container")) {
                            SettingsActions {
                                SettingsTextAction("刷新预览", BeaconIcons.Sync) {}
                                SettingsPrimaryButton("立即清理", BeaconIcons.Delete) {}
                            }
                        }
                    }
                }
            }
        }
        val refresh = rule.onNode(hasText("刷新预览") and hasClickAction()).getUnclippedBoundsInRoot()
        val clean = rule.onNode(hasText("立即清理") and hasClickAction()).getUnclippedBoundsInRoot()
        val container = rule.onNodeWithTag("actions-container").getUnclippedBoundsInRoot()
        assertTrue(refresh.bottom - refresh.top >= 48.dp)
        assertTrue(clean.bottom - clean.top >= 48.dp)
        assertTrue("Primary action must keep content width", clean.right - clean.left < container.right - container.left)
        assertTrue(refresh.left >= container.left && refresh.right <= container.right)
        assertTrue(clean.left >= container.left && clean.right <= container.right)
        assertTrue("Actions must never overlap", clean.left >= refresh.right || clean.top >= refresh.bottom)
    }

    @Test fun materialNormal() = verify(BeaconUiStyle.MATERIAL3, 1f)
    @Test fun materialLargeFont() = verify(BeaconUiStyle.MATERIAL3, 2f)
    @Test fun miuixNormal() = verify(BeaconUiStyle.MIUIX, 1f)
    @Test fun miuixLargeFont() = verify(BeaconUiStyle.MIUIX, 2f)
}
