package io.github.beacon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp

/** A fully rounded floating action: only its own bounds paint or intercept touches. */
@Composable
internal fun BeaconFloatingActionButton(
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

/** An opaque theme surface without background sampling. */
@Composable
internal fun BeaconSurface(
    fallback: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(fallback, shape), content = content)
}
