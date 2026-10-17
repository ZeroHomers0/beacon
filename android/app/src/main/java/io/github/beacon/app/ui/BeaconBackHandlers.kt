package io.github.beacon.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusManager
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.PredictiveBackReset
import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.components.settingsGestureFraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Every back gesture the app answers: predictive detail, predictive settings, predictive page, and
 * the plain fallback.
 *
 * The four handlers are mutually exclusive by construction — the plain [BackHandler] is disabled
 * whenever one of the predictive ones owns the gesture — so they live in one place where that
 * invariant is visible. Spread across files, "exactly one handler is live" would be impossible to
 * check by reading.
 */
@Composable
internal fun BeaconBackHandlers(
    ui: BeaconUiState,
    runtimeState: RuntimeState,
    actions: RuntimeActions,
    pager: PagerState,
    pageNavigationMotion: AnimationSpec<Float>,
    detailMotion: SeekableTransitionState<IncomingMessage?>,
    settingsMotion: SeekableTransitionState<SettingsScreen>,
    settingsBackReset: PredictiveBackReset,
    detailBackReset: PredictiveBackReset,
    navigate: (Int) -> Unit,
    uiScope: CoroutineScope,
    focus: FocusManager,
) {
    val predictiveDetailBack = runtimeState.selectedMessage != null && !runtimeState.messageBusy
    PredictiveBackHandler(enabled = predictiveDetailBack) { events ->
        detailBackReset.stop()
        val original = runtimeState.selectedMessage
        try {
            detailMotion.seekTo(0f, null)
            events.collect { event ->
                detailMotion.seekTo(settingsGestureFraction(event.progress), null)
            }
            ui.closeDetail(actions)
        } catch (_: CancellationException) {
            detailBackReset.launch(uiScope, detailMotion, original)
        }
    }
    val predictiveSettingsBack =
        ui.page == 1 &&
            ui.settingsScreen != SettingsScreen.HOME &&
            runtimeState.selectedMessage == null &&
            !runtimeState.messageBusy
    PredictiveBackHandler(enabled = predictiveSettingsBack) { events ->
        settingsBackReset.stop()
        val original = ui.settingsScreen
        try {
            settingsMotion.seekTo(0f, ui.settingsBackTarget)
            events.collect { event ->
                settingsMotion.seekTo(
                    settingsGestureFraction(event.progress),
                    ui.settingsBackTarget,
                )
            }
            navigate(1)
            if (ui.pendingNavigation != null || ui.savingSettings)
                settingsBackReset.launch(uiScope, settingsMotion, original)
        } catch (_: CancellationException) {
            settingsBackReset.launch(uiScope, settingsMotion, original)
        }
    }
    val predictivePageBack =
        ui.page == 1 &&
            ui.settingsScreen == SettingsScreen.HOME &&
            runtimeState.selectedMessage == null &&
            !runtimeState.messageBusy
    PredictiveBackHandler(enabled = predictivePageBack) { events ->
        focus.clearFocus()
        try {
            events.collect { event ->
                // Pager offsets stay in [-.5, .5]; switch the anchor halfway through.
                val position = 1f - event.progress.coerceIn(0f, 1f)
                val anchor = if (position < .5f) 0 else 1
                pager.scrollToPage(anchor, position - anchor)
            }
            navigate(0)
        } catch (_: CancellationException) {
            uiScope.launch { pager.animateScrollToPage(ui.page, animationSpec = pageNavigationMotion) }
        }
    }
    BackHandler(
        enabled =
            !predictiveSettingsBack &&
                !predictiveDetailBack &&
                !predictivePageBack &&
                (runtimeState.selectedMessage != null ||
                    ui.page != 0 ||
                    ui.selectionMode ||
                    ui.filtersOpen)
    ) {
        when {
            runtimeState.selectedMessage != null -> ui.closeDetail(actions)
            ui.page == 1 && ui.settingsScreen != SettingsScreen.HOME -> navigate(1)
            ui.page != 0 -> navigate(0)
            ui.selectionMode -> {
                ui.selectionMode = false
                ui.selectedKeys = emptyList()
                ui.selectionHint = ""
            }
            ui.filtersOpen -> ui.filtersOpen = false
            else -> Unit
        }
    }
}
