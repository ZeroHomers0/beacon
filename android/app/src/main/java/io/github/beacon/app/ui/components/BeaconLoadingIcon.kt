package io.github.beacon.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

@Composable
fun BeaconRefreshAction(
    description: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    SettingsToolAction(description, io.github.beacon.app.ui.icons.BeaconIcons.Sync,
        enabled = enabled, loading = loading, onClick = onClick)
}

@Composable
fun BeaconSaveAction(
    description: String,
    dirty: Boolean,
    saving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var pending by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var observedSaving by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var saved by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pending, dirty, saving) {
        if (pending && saving) observedSaving = true
        if (pending && !saving && !dirty) {
            pending = false
            observedSaving = false
            saved = true
        } else if (pending && observedSaving && !saving) {
            pending = false
            observedSaving = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(saved, dirty) {
        if (saved) {
            if (!dirty) kotlinx.coroutines.delay(1200)
            saved = false
        }
    }
    BeaconIconButton(
        onClick = { pending = true; saved = false; onClick() },
        enabled = enabled && !saving,
    ) {
        androidx.compose.animation.Crossfade(
            targetState = saved,
            animationSpec = tween(BeaconMotion.QuickMs),
            label = "save-feedback",
        ) { complete ->
            Icon(
                if (complete) io.github.beacon.app.ui.icons.BeaconIcons.Check
                else io.github.beacon.app.ui.icons.BeaconIcons.Save,
                if (complete) androidx.compose.ui.res.stringResource(io.github.beacon.app.R.string.settings_save_completed)
                else description,
                tint = if (complete) io.github.beacon.app.ui.theme.beaconSuccessColor()
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
