package io.github.beacon.app.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
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
        effects = NavDisplayEffects(blockInputDuringTransition = true,
            backdropColor = MaterialTheme.colorScheme.background)) {
        entry<SettingsRoute> { route ->
            val scope = LocalNavTransitionScope.current
            SideEffect { if (route.screen == ui.settingsScreen) ui.settingsNavScope = scope }
            DisposableEffect(scope) { onDispose { if (ui.settingsNavScope === scope) ui.settingsNavScope = null } }
            Box(Modifier.fillMaxSize().testTag("settings-body-${route.screen}")) { content(route.screen) }
        }
    }
}

/** Only nested settings need a path; it occupies the original top inset once. */
@Composable
internal fun BeaconSettingsPageFrame(
    ui: BeaconUiState, screen: SettingsScreen, modifier: Modifier, padding: PaddingValues,
    content: @Composable (Modifier, PaddingValues) -> Unit,
) {
    val path = when (screen) {
        SettingsScreen.SERVER_DEVICES, SettingsScreen.SERVER_APPLICATIONS,
        SettingsScreen.SERVER_TOPICS, SettingsScreen.SERVER_TEST -> listOf(SettingsScreen.HOME, SettingsScreen.SERVER_ADMIN, screen)
        SettingsScreen.QUIET_HOURS -> listOf(SettingsScreen.HOME, SettingsScreen.NOTIFICATION_RULES, screen)
        else -> emptyList()
    }
    if (ui.uiStyle != io.github.beacon.app.domain.model.BeaconUiStyle.MIUIX || path.isEmpty()) {
        content(modifier.testTag("settings-content-$screen"), padding)
        return
    }
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    Column(modifier) {
        top.yukonga.miuix.kmp.basic.BreadcrumbBar(
            items = path.map { top.yukonga.miuix.kmp.basic.BreadcrumbItem(it.name,
                androidx.compose.ui.res.stringResource(it.titleRes)) },
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            onItemClick = { index -> if (index < path.lastIndex) ui.navigateSettingsAncestor(path[index]) },
        )
        content(Modifier.fillMaxWidth().weight(1f), PaddingValues(
            start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction),
            top = io.github.beacon.app.ui.components.BeaconUiMetrics.Gap4,
            bottom = padding.calculateBottomPadding()))
    }
}
