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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey

/** Header and content use the same seekable transition and retain their own page snapshots. */
@Composable
fun BeaconMessageTransition(
    transition: Transition<IncomingMessage?>,
    modifier: Modifier = Modifier,
    animateSize: Boolean = true,
    content: @Composable (IncomingMessage?) -> Unit,
) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    transition.AnimatedContent(
        modifier = modifier.fillMaxWidth(),
        contentKey = { it?.let { message -> MessageKey(message.server, message.id) } },
        transitionSpec = {
            val forward = targetState != null
            val motion = tween<IntOffset>(SETTINGS_NAVIGATION_DURATION_MS, easing = settingsNavigationEasing)
            val entry = slideInHorizontally(motion) {
                direction * if (forward) it else -it / 4
            } + fadeIn(
                tween(SETTINGS_NAVIGATION_DURATION_MS, easing = settingsNavigationEasing),
                initialAlpha = if (forward) 1f else .9f,
            )
            val exit = slideOutHorizontally(motion) {
                direction * if (forward) -it / 4 else it
            } + fadeOut(
                tween(SETTINGS_NAVIGATION_DURATION_MS, easing = settingsNavigationEasing),
                targetAlpha = if (forward) .9f else 1f,
            )
            (entry togetherWith exit)
                .apply { targetContentZIndex = if (forward) 1f else -1f }
                .using(if (animateSize) SizeTransform(clip = true) { _, _ ->
                    tween(SETTINGS_NAVIGATION_DURATION_MS, easing = settingsNavigationEasing)
                } else null)
        },
    ) { message ->
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) { content(message) }
    }
}
