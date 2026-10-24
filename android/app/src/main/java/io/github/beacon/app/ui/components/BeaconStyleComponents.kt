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
import androidx.compose.ui.unit.em
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.theme.LocalBeaconUiStyle

internal val LocalCompactActions = staticCompositionLocalOf { false }

internal val LocalActionFillWidth = staticCompositionLocalOf { false }
internal val LocalButtonLabel = staticCompositionLocalOf { false }

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
    // Relative line height stays consistent when font size or accessibility scale changes.
    val label = LocalButtonLabel.current
    val resolvedMaxLines = if (label) 1 else maxLines
    val resolvedOverflow = if (label) TextOverflow.Ellipsis else overflow
    val textStyle = style.copy(lineHeight = 1.4.em,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
    if (miuix())
        top.yukonga.miuix.kmp.basic.Text(
            text,
            modifier,
            color = color,
            style = if (textAlign == null) textStyle else textStyle.copy(textAlign = textAlign),
            maxLines = resolvedMaxLines,
            overflow = resolvedOverflow,
        )
    else
        androidx.compose.material3.Text(
            text,
            modifier,
            color = color,
            style = if (textAlign == null) textStyle else textStyle.copy(textAlign = textAlign),
            maxLines = resolvedMaxLines,
            overflow = resolvedOverflow,
        )
}

/** All text actions share one treatment; legacy names preserve existing call sites. */
@Composable
internal fun BeaconUnifiedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    selected: Boolean? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val foreground = if (!enabled) (if (selected != null) BeaconTagInk else scheme.onSurface).copy(alpha = .38f)
        else if (danger) scheme.error else if (selected != null) BeaconTagInk else scheme.primary
    val background = when (selected) { true -> BeaconChoiceSelected; false -> BeaconChoiceUnselected; null -> scheme.surfaceContainerHigh }
    val actionModifier = (if (LocalActionFillWidth.current) modifier.fillMaxWidth() else modifier)
        .heightIn(min = BeaconUiMetrics.ControlMinHeight)
        .widthIn(min = BeaconUiMetrics.ActionMinWidth)
    val insets = PaddingValues(horizontal = BeaconUiMetrics.Gap12, vertical = BeaconUiMetrics.Gap4)
    if (miuix()) {
        top.yukonga.miuix.kmp.basic.Button(onClick, actionModifier, enabled,
            cornerRadius = BeaconUiMetrics.ActionRadius,
            minHeight = BeaconUiMetrics.ControlMinHeight, insideMargin = insets,
            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                color = background, disabledColor = background),
        ) {
            CompositionLocalProvider(
                LocalButtonLabel provides true,
                androidx.compose.material3.LocalContentColor provides foreground,
                top.yukonga.miuix.kmp.theme.LocalContentColor provides foreground,
            ) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
            }
        }
    } else androidx.compose.material3.Button(onClick, actionModifier, enabled,
        shape = BeaconUiMetrics.ActionShape, contentPadding = insets,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = background, disabledContainerColor = background,
            contentColor = foreground, disabledContentColor = foreground), content = {
                CompositionLocalProvider(LocalButtonLabel provides true) { content() }
            })
}

@Composable
fun BeaconButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit,
) = BeaconUnifiedButton(onClick, modifier, enabled, content = content)

@Composable
fun BeaconTextButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit,
) = BeaconUnifiedButton(onClick, modifier, enabled, content = content)

@Composable
fun BeaconOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit,
) = BeaconUnifiedButton(onClick, modifier, enabled, content = content)

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

/** Destructive actions keep the same geometry and background, with a red label. */
@Composable
fun BeaconDangerButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit,
) = BeaconUnifiedButton(onClick, modifier, enabled, danger = true, content = content)
