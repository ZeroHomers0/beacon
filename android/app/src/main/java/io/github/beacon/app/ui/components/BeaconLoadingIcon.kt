package io.github.beacon.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun BeaconLoadingIcon(
    icon: ImageVector,
    loading: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val angle = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(loading, animationsEnabled) {
        if (!animationsEnabled) {
            angle.snapTo(0f)
        } else if (loading) {
            while (true) {
                angle.animateTo(angle.value - 360f, tween(BeaconMotion.PulseMs, easing = LinearEasing))
                angle.snapTo(angle.value % 360f)
            }
        } else {
            // Continue in the same direction to the next full turn before becoming static.
            val remaining = ((angle.value % 360f) + 360f) % 360f
            if (remaining > .01f) {
                angle.animateTo(angle.value - remaining,
                    tween((BeaconMotion.PulseMs * remaining / 360f).toInt().coerceAtLeast(1), easing = LinearEasing))
            }
            angle.snapTo(0f)
        }
    }
    androidx.compose.animation.Crossfade(
        targetState = icon,
        animationSpec = tween(BeaconMotion.StandardMs),
        label = "busy-icon-shape",
    ) { displayed ->
        Icon(displayed, description,
            if (displayed == io.github.beacon.app.ui.icons.BeaconIcons.Sync) modifier.rotate(angle.value) else modifier,
            tint = tint)
    }
}

/** Click ownership and minimum visible feedback are shared by icon and full-width actions. */
@Composable
internal fun rememberActionProgress(loading: Boolean): androidx.compose.runtime.MutableState<Boolean> {
    val requested = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val currentLoading by androidx.compose.runtime.rememberUpdatedState(loading)
    androidx.compose.runtime.LaunchedEffect(requested.value) {
        if (!requested.value) return@LaunchedEffect
        try {
            kotlinx.coroutines.coroutineScope {
                val visible = async { kotlinx.coroutines.delay(320) }
                val started = kotlinx.coroutines.withTimeoutOrNull(750) {
                    androidx.compose.runtime.snapshotFlow { currentLoading }.first { it }
                } == true
                if (started) androidx.compose.runtime.snapshotFlow { currentLoading }.first { !it }
                visible.await()
            }
        } finally { requested.value = false }
    }
    return requested
}

@Composable
fun BeaconRefreshAction(description: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    var requested by rememberActionProgress(loading)
    SettingsToolAction(description, io.github.beacon.app.ui.icons.BeaconIcons.Sync,
        enabled = enabled && !requested, loading = requested,
        onClick = { requested = true; onClick() })
}

@Composable
fun BeaconSaveAction(
    description: String,
    dirty: Boolean,
    saving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val actionable = dirty && enabled && !saving
    BeaconIconButton(onClick = onClick, enabled = actionable) {
        Icon(
            io.github.beacon.app.ui.icons.BeaconIcons.Save,
            description,
            tint = if (actionable) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
        )
    }
}
