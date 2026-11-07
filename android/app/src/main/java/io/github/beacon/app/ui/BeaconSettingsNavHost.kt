package io.github.beacon.app.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.nav.core.*
import io.github.beacon.app.ui.components.SettingsScreen

private data class SettingsRoute(val screen: SettingsScreen) : NavKey

/** SavedStateHandle remains the persistent source, including unsaved-draft navigation guards. */
@Composable
internal fun BeaconSettingsNavHost(ui: BeaconUiState, content: @Composable (SettingsScreen) -> Unit) {
    val desired = (ui.settingsHistory + ui.settingsScreen).distinct().map(::SettingsRoute)
    val stack = remember(ui) { navBackStackOf(*desired.toTypedArray()) }
    SideEffect {
        var common = 0
        while (common < stack.size && common < desired.size && stack[common] == desired[common]) common++
        while (stack.size > common) stack.removeAt(stack.lastIndex)
        for (index in common until desired.size) stack.add(desired[index])
    }
    NavDisplay(backStack = stack, modifier = Modifier.fillMaxSize().clipToBounds(),
        onBack = { ui.backSettings() },
        effects = NavDisplayEffects(blockInputDuringTransition = false,
            backdropColor = MaterialTheme.colorScheme.background)) {
        entry<SettingsRoute> { route ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("settings-body-${route.screen}")) { content(route.screen) }
        }
    }
}

/** Keep shared page geometry without adding a second navigation control row. */
@Composable
internal fun BeaconSettingsPageFrame(
    ui: BeaconUiState, screen: SettingsScreen, modifier: Modifier, padding: PaddingValues,
    content: @Composable (Modifier, PaddingValues) -> Unit,
) {
    content(modifier.testTag("settings-content-$screen"), padding)
}
