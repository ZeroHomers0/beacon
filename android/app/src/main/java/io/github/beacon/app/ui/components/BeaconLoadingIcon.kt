package io.github.beacon.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
    val animatedModifier =
        if (loading) {
            val transition = rememberInfiniteTransition(label = "busy-icon")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = -360f,
                animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
                label = "busy-icon-rotation",
            )
            modifier.rotate(angle)
        } else modifier
    Icon(icon, description, animatedModifier, tint = tint)
}

@Composable
fun BeaconRefreshAction(
    description: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    BeaconIconButton(onClick = onClick, enabled = enabled && !loading) {
        BeaconLoadingIcon(
            io.github.beacon.app.ui.icons.BeaconIcons.Sync,
            loading,
            description = description,
        )
    }
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
            animationSpec = tween(150),
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
