package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.BeaconFeedbackHost
import io.github.beacon.app.ui.components.showFeedback
import io.github.beacon.app.ui.theme.BeaconTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OfficialFeedbackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun feedback(style: BeaconUiStyle) {
        lateinit var state: SnackbarHostState
        lateinit var scope: CoroutineScope
        var actionResult: SnackbarResult? = null
        rule.runOnUiThread { rule.activity.setContent {
            BeaconTheme(style) {
                state = remember { SnackbarHostState() }
                scope = rememberCoroutineScope()
                Box(Modifier.fillMaxSize()) { BeaconFeedbackHost(state) }
            }
        } }
        rule.runOnIdle { scope.launch { state.showFeedback("旧提示", duration = SnackbarDuration.Indefinite) } }
        rule.onNodeWithText("旧提示").assertIsDisplayed()
        rule.runOnIdle { scope.launch { actionResult = state.showFeedback("新提示", "执行") } }
        rule.onNodeWithText("新提示").assertIsDisplayed()
        rule.onNodeWithText("旧提示").assertDoesNotExist()
        rule.onNodeWithText("执行").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(SnackbarResult.ActionPerformed, actionResult); assertNull(state.currentSnackbarData) }
    }
    @Test fun materialFeedbackReplacesAndReturnsAction() = feedback(BeaconUiStyle.MATERIAL3)
    @Test fun miuixFeedbackReplacesAndReturnsAction() = feedback(BeaconUiStyle.MIUIX)
}
