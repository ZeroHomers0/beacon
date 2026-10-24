package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.feature.settings.*
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsInteractionRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun inspect(style: BeaconUiStyle) {
        val value = "https://a-very-long-server-name.example.com/long-path\n完整地址仍可复制"
        rule.runOnUiThread { rule.activity.setContent {
            BeaconTheme(style) { Column(Modifier.width(220.dp).testTag("field")) { SettingsOverviewField("服务器地址", value) } }
        } }
        val before = rule.onNodeWithTag("field").getUnclippedBoundsInRoot()
        rule.onNodeWithText(value.replace("\n", " · ")).performClick()
        rule.onNodeWithText(value).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.action_close)).performClick()
        assertEquals(before, rule.onNodeWithTag("field").getUnclippedBoundsInRoot())
    }
    private fun toggle(style: BeaconUiStyle) {
        var checked by mutableStateOf(false)
        var callbacks = 0
        rule.runOnUiThread { rule.activity.setContent {
            BeaconTheme(style) { BeaconPreferenceToggle("测试开关", "", checked) { checked = it; callbacks++ } }
        } }
        rule.onNodeWithContentDescription("测试开关").performClick()
        rule.runOnIdle { assertTrue(checked); assertEquals(1, callbacks) }
        rule.onNodeWithContentDescription("测试开关").performClick()
        rule.runOnIdle { assertFalse(checked); assertEquals(2, callbacks) }
    }
    private fun rules(style: BeaconUiStyle) {
        val form = ReceptionFormState()
        rule.runOnUiThread { rule.activity.setContent {
            BeaconTheme(style) { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                Box(Modifier.testTag("rules")) { ReceiveRulesPanel(form, false) }
            } }
        } }
        val before = rule.onNodeWithTag("rules").getUnclippedBoundsInRoot()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.settings_rules_battery)).performClick()
        assertEquals(before, rule.onNodeWithTag("rules").getUnclippedBoundsInRoot())
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.settings_rules_schedule)).performScrollTo().performClick()
        val after = rule.onNodeWithTag("rules").getUnclippedBoundsInRoot()
        assertEquals(before.bottom - before.top, after.bottom - after.top)
    }
    private fun examples(style: BeaconUiStyle) {
        val repository = io.github.beacon.app.data.repository.RuntimeRepository()
        val key = "a".repeat(64)
        var reads = 0
        val actions = object : io.github.beacon.app.ipc.RuntimeActions by repository {
            override fun copyPushKey(onReady: (String) -> Unit) { reads++; onReady(key) }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent {
                BeaconTheme(style) { PushExamplesPanel(actions, true, "https://example.com") }
            } }
            val clipboard = rule.activity.getSystemService(android.content.ClipboardManager::class.java)
            rule.onNodeWithText(rule.activity.getString(R.string.binding_copy_push_address)).performClick()
            rule.runOnIdle { assertEquals(key, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            rule.onNodeWithText(rule.activity.getString(R.string.push_examples_title)).performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.settings_example_shell)).assertDoesNotExist()
            rule.onNodeWithText(rule.activity.getString(R.string.settings_example_format)).assertDoesNotExist()
            val examples = listOf(R.string.push_example_curl to "curl --fail-with-body",
                R.string.push_example_powershell to "Invoke-RestMethod",
                R.string.push_example_json_curl to "--data",
                R.string.push_example_json_powershell to "UTF8.GetBytes")
            for ((label, expected) in examples) {
                rule.onNodeWithText(rule.activity.getString(label)).performScrollTo().performClick()
                rule.runOnIdle { assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains(expected)) }
            }
            rule.runOnIdle { assertEquals(5, reads) }
            rule.onNodeWithText(rule.activity.getString(R.string.action_close)).performClick()
        } finally { rule.runOnUiThread { repository.close() } }
    }
    @Test fun materialDeveloperToolsCopyAllFourExamples() = examples(BeaconUiStyle.MATERIAL3)
    @Test fun miuixDeveloperToolsCopyAllFourExamples() = examples(BeaconUiStyle.MIUIX)

    @Test fun materialLongValueDetails() = inspect(BeaconUiStyle.MATERIAL3)
    @Test fun miuixLongValueDetails() = inspect(BeaconUiStyle.MIUIX)
    @Test fun materialSwitchOnlyEmitsOnce() = toggle(BeaconUiStyle.MATERIAL3)
    @Test fun miuixSwitchOnlyEmitsOnce() = toggle(BeaconUiStyle.MIUIX)
    @Test fun materialRulesNeverExpand() = rules(BeaconUiStyle.MATERIAL3)
    @Test fun miuixRulesNeverExpand() = rules(BeaconUiStyle.MIUIX)
}
