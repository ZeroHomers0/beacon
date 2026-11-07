package io.github.beacon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import top.yukonga.miuix.kmp.blur.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role


/** One failure disables the optical path for this scaffold; no saved preference or retry loop. */
internal class BeaconGlassState(supported: Boolean) {
    var available by mutableStateOf(supported)
        private set

    fun disable() { available = false }
}

internal val LocalBeaconTopBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

internal val LocalBeaconGlass = staticCompositionLocalOf<BeaconGlassState?> { null }
internal val LocalBeaconGlassBackdrop = staticCompositionLocalOf<Any?> { null }

/** A fully rounded floating action: only its own bounds paint or intercept touches. */
@Composable
internal fun BeaconFloatingGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val foreground = scheme.onSurface.copy(alpha = if (enabled) 1f else .38f)
    val padding = androidx.compose.foundation.layout.PaddingValues(
        horizontal = BeaconUiMetrics.FloatingActionHorizontalInset, vertical = BeaconUiMetrics.Gap12)
    val body: @Composable RowScope.() -> Unit = {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides foreground,
            top.yukonga.miuix.kmp.theme.LocalContentColor provides foreground,
        ) { content() }
    }
    if (miuix()) top.yukonga.miuix.kmp.basic.Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.shadow(BeaconUiMetrics.FloatingActionElevation, CircleShape).clip(CircleShape),
        cornerRadius = 0.dp, minHeight = BeaconUiMetrics.NewMessagesMinHeight,
        insideMargin = padding,
        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
            color = scheme.surfaceContainerHigh, disabledColor = scheme.surfaceContainerHigh,
            contentColor = foreground, disabledContentColor = foreground),
        content = body,
    ) else androidx.compose.material3.FilledTonalButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = BeaconUiMetrics.NewMessagesMinHeight)
            .shadow(BeaconUiMetrics.FloatingActionElevation, CircleShape),
        shape = CircleShape, contentPadding = padding,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = scheme.surfaceContainerHigh, contentColor = foreground,
            disabledContainerColor = scheme.surfaceContainerHigh, disabledContentColor = foreground),
        content = body,
    )
}

internal fun supportsBeaconGlass(sdk: Int, hardware: Boolean, lowRam: Boolean, shader: Boolean) = false

@Composable
internal fun rememberBeaconGlassState(): BeaconGlassState = remember { BeaconGlassState(false) }

/** Ordinary theme surface; no background capture, shader, blur or refraction. */
@Composable
internal fun Modifier.beaconGlassMaterial(fallback: Color, shape: Shape = RectangleShape): Modifier =
    background(fallback, shape)

@Composable
internal fun BeaconGlassSurface(
    fallback: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    topFade: Boolean = false,
    topFadeProgress: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalBeaconTopBackdrop.current
    val hardware = LocalView.current.isHardwareAccelerated
    Box(modifier.background(fallback, shape)) {
        Box(Modifier.matchParentSize().clipToBounds().graphicsLayer {
            alpha = if (topFade) topFadeProgress().coerceIn(0f, 1f) else 1f
        }.then(
            if (topFade && miuix() && hardware && backdrop != null) Modifier
                .progressiveTextureBlur(backdrop = backdrop, shape = RectangleShape,
                    blurRadius = BeaconUiMetrics.TopGlassBlurRadius.value,
                    gradient = ProgressiveBlur.Top.copy(startFraction = .1f))
                .background(Brush.verticalGradient(
                    0f to fallback.copy(alpha = .98f),
                    .65f to fallback.copy(alpha = .94f),
                    .9f to fallback.copy(alpha = .60f),
                    1f to fallback.copy(alpha = 0f),
                ))
            else Modifier.background(fallback, shape)
        ))
        content()
    }
}
