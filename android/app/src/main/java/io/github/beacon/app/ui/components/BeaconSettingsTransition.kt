package io.github.beacon.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

internal fun settingsPageDepth(screen: SettingsScreen): Int = when (screen) {
    SettingsScreen.HOME -> 0
    SettingsScreen.SERVER_DEVICES,
    SettingsScreen.SERVER_APPLICATIONS,
    SettingsScreen.SERVER_TEST,
    SettingsScreen.SERVER_TOPICS,
    SettingsScreen.QUIET_HOURS -> 2
    else -> 1
}

/** KSU/MIUIX geometry: full-width child entry, quarter-width parent parallax, reversed on back. */
@Composable
fun BeaconSettingsTransition(
    transition: Transition<SettingsScreen>,
    navigatingBack: Boolean = false,
    animateSize: Boolean = true,
    content: @Composable (SettingsScreen) -> Unit,
) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    transition.AnimatedContent(
        modifier = (if (animateSize) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).clipToBounds(),
        transitionSpec = {
            val entering = if (settingsPageDepth(targetState) == settingsPageDepth(initialState))
                !navigatingBack
                else settingsPageDepth(targetState) > settingsPageDepth(initialState)
            val motion =
                tween<androidx.compose.ui.unit.IntOffset>(
                    SETTINGS_NAVIGATION_DURATION_MS,
                    easing = settingsNavigationEasing,
                )
            val entry =
                slideInHorizontally(motion) { width ->
                    direction * if (entering) width else -width / 4
                } +
                    fadeIn(
                        tween(SETTINGS_NAVIGATION_DURATION_MS, easing = settingsNavigationEasing),
                        initialAlpha = 1f,
                    )
            val exit =
                slideOutHorizontally(motion) { width ->
                    direction * if (entering) -width / 4 else width
                } +
                    fadeOut(
                        tween(SETTINGS_NAVIGATION_DURATION_MS, easing = settingsNavigationEasing),
                        targetAlpha = 1f,
                    )
            (entry togetherWith exit)
                .apply { targetContentZIndex = settingsPageDepth(targetState).toFloat() }
                .using(
                    if (animateSize) SizeTransform(clip = true) { _, _ ->
                        tween(SETTINGS_NAVIGATION_DURATION_MS, easing = settingsNavigationEasing)
                    } else null
                )
        },
        contentKey = { it },
    ) { screen ->
        Box(
            (if (animateSize) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .background(MaterialTheme.colorScheme.background)
        ) {
            content(screen)
        }
    }
}
