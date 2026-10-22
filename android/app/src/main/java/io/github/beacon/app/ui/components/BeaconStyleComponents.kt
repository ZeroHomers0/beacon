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

internal val LocalDialogPrimary = staticCompositionLocalOf { false }

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

/** Primary actions share geometry and typography in both visual styles. */
@Composable
fun BeaconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val actionModifier = (if (LocalDialogAction.current) modifier.fillMaxWidth() else modifier)
        .heightIn(min = BeaconUiMetrics.ControlMinHeight)
        .widthIn(min = BeaconUiMetrics.ActionMinWidth)
    val insets = PaddingValues(horizontal = BeaconUiMetrics.Gap12, vertical = BeaconUiMetrics.Gap4)
    val scheme = MaterialTheme.colorScheme
    if (miuix()) {
        top.yukonga.miuix.kmp.basic.Button(onClick, actionModifier, enabled,
            cornerRadius = BeaconUiMetrics.ActionRadius,
            minHeight = BeaconUiMetrics.ControlMinHeight,
            insideMargin = insets,
            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                color = scheme.primary, disabledColor = scheme.onSurface.copy(alpha = .12f)),
        ) {
            val foreground = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = .38f)
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides foreground,
                top.yukonga.miuix.kmp.theme.LocalContentColor provides foreground,
            ) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
            }
        }
    } else androidx.compose.material3.Button(onClick, actionModifier, enabled,
        shape = BeaconUiMetrics.ActionShape, contentPadding = insets, content = content)
}

/** Secondary actions never gain a filled background merely because they appear in a dialog. */
@Composable
fun BeaconTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalDialogPrimary.current) {
        BeaconButton(onClick, modifier, enabled, content)
        return
    }
    val actionModifier = (if (LocalDialogAction.current) modifier.fillMaxWidth() else modifier)
        .heightIn(min = BeaconUiMetrics.ControlMinHeight)
        .widthIn(min = BeaconUiMetrics.ActionMinWidth)
    val insets = PaddingValues(horizontal = BeaconUiMetrics.Gap12, vertical = BeaconUiMetrics.Gap4)
    val foreground = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    if (miuix()) {
        top.yukonga.miuix.kmp.basic.Button(onClick, actionModifier, enabled,
            cornerRadius = BeaconUiMetrics.ActionRadius,
            minHeight = BeaconUiMetrics.ControlMinHeight, insideMargin = insets,
            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                color = Color.Transparent, disabledColor = Color.Transparent),
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides foreground,
                top.yukonga.miuix.kmp.theme.LocalContentColor provides foreground,
            ) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
            }
        }
    } else androidx.compose.material3.TextButton(onClick, actionModifier, enabled,
        shape = BeaconUiMetrics.ActionShape, contentPadding = insets,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = foreground, disabledContentColor = foreground), content = content)
}

/** Compatibility adapter for existing call sites; secondary actions use one transparent treatment. */
@Composable
fun BeaconOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = BeaconTextButton(onClick, modifier, enabled, content)

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
            else modifier.heightIn(min = BeaconUiMetrics.ControlMinHeight).widthIn(min = BeaconUiMetrics.ActionMinWidth),
        enabled = enabled,
        shape = BeaconUiMetrics.ActionShape,
        colors =
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        contentPadding = PaddingValues(horizontal = BeaconUiMetrics.Gap12, vertical = BeaconUiMetrics.Gap4),
        content = content,
    )
}
