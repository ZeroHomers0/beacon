@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.theme.LocalBeaconUiStyle

internal val LocalCompactActions = staticCompositionLocalOf { false }

internal val LocalDialogAction = staticCompositionLocalOf { false }

/**
 * MIUIX draws a field with a placeholder label taller than one without, so two fields in the same
 * card came out 51.8dp and 56.4dp high. Both now keep the Material 3 field height.
 */
internal val TEXT_FIELD_MIN_HEIGHT = 56.dp

/** Visual adapters share all screen state and callbacks; switching never restarts data work. */
@Composable internal fun miuix() = LocalBeaconUiStyle.current == BeaconUiStyle.MIUIX

@Composable
fun BeaconText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    if (miuix())
        top.yukonga.miuix.kmp.basic.Text(
            text,
            modifier,
            color = color,
            style = if (textAlign == null) style else style.copy(textAlign = textAlign),
            maxLines = maxLines,
            overflow = overflow,
        )
    else
        androidx.compose.material3.Text(
            text,
            modifier,
            color = color,
            style = if (textAlign == null) style else style.copy(textAlign = textAlign),
            maxLines = maxLines,
            overflow = overflow,
        )
}

@Composable
fun BeaconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (miuix())
        top.yukonga.miuix.kmp.basic.Button(
            onClick,
            if (LocalDialogAction.current) modifier.fillMaxWidth().heightIn(min = BeaconUiMetrics.ControlMinHeight)
            else modifier.heightIn(min = BeaconUiMetrics.ControlMinHeight),
            enabled,
            minHeight = 48.dp,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides
                    top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onPrimary,
                top.yukonga.miuix.kmp.theme.LocalContentColor provides
                    top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onPrimary,
            ) {
                content()
            }
        }
    else
        androidx.compose.material3.Button(
            onClick,
            if (LocalDialogAction.current) modifier.fillMaxWidth().heightIn(min = BeaconUiMetrics.ControlMinHeight)
            else modifier.heightIn(min = BeaconUiMetrics.ControlMinHeight),
            enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            content = content,
        )
}

@Composable
fun BeaconTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalDialogAction.current) {
        if (miuix())
            top.yukonga.miuix.kmp.basic.Button(
                onClick,
                modifier.fillMaxWidth().heightIn(min = BeaconUiMetrics.ControlMinHeight),
                enabled,
                minHeight = 48.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                val color =
                    top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSecondaryVariant
                CompositionLocalProvider(
                    top.yukonga.miuix.kmp.theme.LocalContentColor provides color,
                    androidx.compose.material3.LocalContentColor provides color,
                ) {
                    content()
                }
            }
        else
            androidx.compose.material3.FilledTonalButton(
                onClick,
                modifier.fillMaxWidth().heightIn(min = BeaconUiMetrics.ControlMinHeight),
                enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                content = content,
            )
    } else if (miuix())
        top.yukonga.miuix.kmp.basic.Button(
            onClick,
            modifier.heightIn(min = BeaconUiMetrics.ControlMinHeight),
            enabled,
            minHeight = 48.dp,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors =
                top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                    color = Color.Transparent,
                    disabledColor = Color.Transparent,
                ),
        ) {
            CompositionLocalProvider(
                top.yukonga.miuix.kmp.theme.LocalContentColor provides
                    (if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
            ) {
                content()
            }
        }
    else
        androidx.compose.material3.TextButton(
            onClick,
            modifier.heightIn(min = BeaconUiMetrics.ControlMinHeight),
            enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            content = content,
        )
}

@Composable
fun BeaconOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (miuix())
        top.yukonga.miuix.kmp.basic.Button(
            onClick,
            (if (LocalDialogAction.current) modifier.fillMaxWidth() else modifier).heightIn(min = BeaconUiMetrics.ControlMinHeight),
            enabled,
            minHeight = 48.dp,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            content = content,
        )
    else
        androidx.compose.material3.FilledTonalButton(
            onClick,
            (if (LocalDialogAction.current) modifier.fillMaxWidth() else modifier).heightIn(
                min = BeaconUiMetrics.ControlMinHeight,
            ),
            enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            content = content,
        )
}

@Composable
fun BeaconIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val actionModifier = if (LocalCompactActions.current) modifier.width(44.dp) else modifier
    if (miuix())
        top.yukonga.miuix.kmp.basic.IconButton(
            onClick,
            actionModifier.semantics(mergeDescendants = true) { if (!enabled) disabled() },
            enabled,
            minHeight = 48.dp,
            minWidth = if (LocalCompactActions.current) 44.dp else 48.dp,
            content = content,
        )
    else androidx.compose.material3.IconButton(onClick, actionModifier, enabled, content = content)
}

/** A control drawn beside its label owns no text, so the caller supplies the accessible name. */
internal fun Modifier.named(name: String): Modifier =
    if (name.isEmpty()) this else semantics { contentDescription = name }

@Composable
fun BeaconCard(
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(BeaconUiMetrics.CardRadius),
    colors: androidx.compose.material3.CardColors =
        androidx.compose.material3.CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (miuix())
        top.yukonga.miuix.kmp.basic.Card(
            modifier,
            cornerRadius = BeaconUiMetrics.CardRadius,
            colors =
                top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                    color = colors.containerColor
                ),
            content = content,
        )
    else androidx.compose.material3.Card(modifier, shape, colors, content = content)
}

@Composable
fun BeaconCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(BeaconUiMetrics.CardRadius),
    colors: androidx.compose.material3.CardColors =
        androidx.compose.material3.CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (miuix()) {
        // The library has no enabled parameter; a passive Card preserves disabled semantics.
        if (enabled)
            top.yukonga.miuix.kmp.basic.Card(
                modifier,
                cornerRadius = BeaconUiMetrics.CardRadius,
                colors =
                    top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                        color = colors.containerColor
                    ),
                onClick = onClick,
                content = content,
            )
        else BeaconCard(modifier.semantics { disabled() }, shape, colors, content)
    } else
        androidx.compose.material3.Card(
            onClick,
            modifier,
            enabled,
            shape,
            colors,
            content = content,
        )
}

@Composable
fun BeaconActionButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val showIcon = maxWidth >= 100.dp * fontScale
        BeaconOutlinedButton(onClick, Modifier.fillMaxWidth(), enabled) {
            if (showIcon) {
                androidx.compose.material3.Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            BeaconText(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun BeaconCheckConnectionButton(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    BeaconOutlinedButton(onClick, Modifier.fillMaxWidth(), enabled && !loading) {
        BeaconText(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Irreversible confirmation. The filled error container is the only difference from [BeaconButton],
 * so it must stay reserved for actions that cannot be undone.
 */
@Composable
fun BeaconDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier =
            if (LocalDialogAction.current) modifier.fillMaxWidth().heightIn(min = BeaconUiMetrics.ControlMinHeight)
            else modifier.heightIn(min = BeaconUiMetrics.ControlMinHeight),
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors =
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        content = content,
    )
}
