package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.feature.messages.MessagesPage
import io.github.beacon.app.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupLoadingFeedbackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun shortInitialReadDoesNotFlashLoadingButLongReadShowsFeedback() {
        val repository = RuntimeRepository()
        try {
            rule.mainClock.autoAdvance = false
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(BeaconUiStyle.MATERIAL3) {
                        MessagesPage(
                            scroll = rememberLazyListState(),
                            modifier = Modifier,
                            padding = PaddingValues(16.dp),
                            runtimeState = RuntimeState(runtimeConnected = true, dataBusy = true),
                            actions = repository,
                            search = "",
                            readFilter = MessageReadFilter.ALL,
                            selectionMode = false,
                            selectedKeys = emptyList(),
                            selectionHint = "",
                            onSearch = {},
                            onApplyFilter = { _, _ -> },
                            onConnectionSettings = {},
                            onEnterSelection = {},
                            onSelectionChange = {},
                            onSelectionHint = {},
                            onCopyText = { _, _ -> },
                        )
                    }
                }
            }
            val loading = rule.onNodeWithText(rule.activity.getString(R.string.msg_loading))
            rule.mainClock.advanceTimeBy(200)
            loading.assertDoesNotExist()
            rule.mainClock.advanceTimeBy(400)
            loading.assertExists()
        } finally {
            rule.mainClock.autoAdvance = true
            rule.runOnUiThread { repository.close() }
        }
    }
}
