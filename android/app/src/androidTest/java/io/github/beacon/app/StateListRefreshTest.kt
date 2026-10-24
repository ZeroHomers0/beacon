package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.feature.messages.StatesPage
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test

class StateListRefreshTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun verify(style: BeaconUiStyle) {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        rule.runOnUiThread {
            rule.activity.setContent { BeaconTheme(style) {
                StatesPage(Modifier.fillMaxSize(), onRefresh = { calls.incrementAndGet() })
            } }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { swipeDown(durationMillis=600) }
        rule.waitUntil(10000) { calls.get() == 1 }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
    }
    @Test fun materialEmptyStateListRefreshes() = verify(BeaconUiStyle.MATERIAL3)
    @Test fun miuixEmptyStateListRefreshes() = verify(BeaconUiStyle.MIUIX)
}
