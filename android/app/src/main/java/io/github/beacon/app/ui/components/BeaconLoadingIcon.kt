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
                targetValue = 360f,
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
