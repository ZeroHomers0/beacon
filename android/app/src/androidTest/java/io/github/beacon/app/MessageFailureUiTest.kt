package io.github.beacon.app

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.messages.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessageFailureUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle, dialog: Boolean) {
        var calls = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    CompositionLocalProvider(LocalContext provides object : ContextWrapper(rule.activity) {
                        override fun startActivity(intent: Intent) { calls++; throw ActivityNotFoundException("test") }
                    }) {
                        if (dialog) MessageLinkDialog("https://example.test", {})
                        else Box(Modifier.width(300.dp).testTag("actions")) {
                            MessageActionsPanel(listOf(MessageLinkAction("打开网页", "https://example.test")))
                        }
                    }
                }
            }
        }
        val action = if (dialog) rule.activity.getString(R.string.message_open_browser) else "打开网页"
        val before = if (dialog) rule.onNode(hasText(action) and hasClickAction()).getUnclippedBoundsInRoot()
            else rule.onNodeWithTag("actions").getUnclippedBoundsInRoot()
        rule.onNode(hasText(action) and hasClickAction()).performClick()
        rule.waitUntil { calls == 1 }
        rule.onNodeWithText(rule.activity.getString(R.string.message_link_failed)).assertIsDisplayed()
        val after = if (dialog) rule.onNode(hasText(action) and hasClickAction()).getUnclippedBoundsInRoot()
            else rule.onNodeWithTag("actions").getUnclippedBoundsInRoot()
        assertEquals(before, after)
    }
    @Test fun materialLinkFailureKeepsDialogGeometry() = verify(BeaconUiStyle.MATERIAL3, true)
    @Test fun miuixLinkFailureKeepsDialogGeometry() = verify(BeaconUiStyle.MIUIX, true)
    @Test fun materialActionFailureKeepsContentGeometry() = verify(BeaconUiStyle.MATERIAL3, false)
    @Test fun miuixActionFailureKeepsContentGeometry() = verify(BeaconUiStyle.MIUIX, false)
}
