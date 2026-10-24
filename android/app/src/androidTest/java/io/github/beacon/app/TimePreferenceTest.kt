package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTimePreference
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test

class TimePreferenceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun select(style: BeaconUiStyle) {
        var first by mutableStateOf("22:00")
        var last by mutableStateOf("08:00")
        var changes = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    Column(Modifier.width(320.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BeaconTimePreference(first, { first = it; changes++ }, "开始时间", Modifier.weight(1f))
                            BeaconTimePreference(last, { last = it; changes++ }, "结束时间", Modifier.weight(1f))
                        }
                        BeaconText("已选 $first–$last")
                    }
                }
            }
        }
        rule.onNode(hasText("22:00") and hasClickAction()).performClick()
        rule.onAllNodesWithText("开始时间").onLast().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals(0, changes) }
        rule.onNodeWithText("已选 22:00–08:00").assertIsDisplayed()
        rule.onNode(hasText("08:00") and hasClickAction()).performClick()
        if (style == BeaconUiStyle.MIUIX) {
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.time_picker_hour))
                .performTouchInput { swipeDown() }
        } else {
            val seven = Regex("(^|[^0-9])0?7([^0-9]|$)")
            val hour = SemanticsMatcher("clock face hour seven") { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                    ?.any { seven.containsMatchIn(it) } == true ||
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.any { it.text == "7" || it.text == "07" } == true
            }
            rule.onAllNodes(hour).onFirst().performClick()
        }
        rule.onNodeWithText(rule.activity.getString(R.string.settings_confirm)).performClick()
        rule.runOnIdle {
            org.junit.Assert.assertEquals(1, changes)
            org.junit.Assert.assertEquals("22:00", first)
            org.junit.Assert.assertNotEquals("08:00", last)
            org.junit.Assert.assertTrue(last.matches(Regex("[0-2][0-9]:[0-5][0-9]")))
        }
        rule.onNodeWithText("已选 $first–$last").assertIsDisplayed()
    }

    @Test fun materialTimePicker() = select(BeaconUiStyle.MATERIAL3)

    @Test fun miuixTimePicker() = select(BeaconUiStyle.MIUIX)
}
