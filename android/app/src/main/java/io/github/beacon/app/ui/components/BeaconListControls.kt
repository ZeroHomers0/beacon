@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.beacon.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.LocalBeaconUiStyle

/** Finds the search field regardless of its hint text, which is user-facing copy. */
const val SEARCH_BOX_TAG = "search-box"
const val SEARCH_COLLAPSE_TAG = "search-collapse-host"

/** Persistent search surface; no extra card/title/submit button around a list query. */
@Composable
fun BeaconSearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    scroll: androidx.compose.foundation.lazy.LazyListState? = null,
    onSearch: () -> Unit,
) {
    val progress = if (scroll != null) rememberBeaconScrollProgress(scroll) else LocalBeaconScrollProgress.current
    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val hardware = LocalView.current.isHardwareAccelerated
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val fullWidth = maxWidth
    val fraction = if (focused) 1f else 1f - progress()
    val width = fullWidth
    val fieldHeight = maxOf(BeaconUiMetrics.SearchFieldMinHeight,
        with(density) { BeaconListText.SearchLineHeight.toDp() } + BeaconUiMetrics.SearchFieldVerticalInset * 2)
    val height = fieldHeight * fraction.coerceIn(0f, 1f)
    val lineHeight = with(density) { BeaconListText.SearchLineHeight.toDp() }
    val fadeRange = minOf(fieldHeight - lineHeight, lineHeight * .25f)
    // Full height must be fully sharp at every font scale, not only the default font size.
    val textAlpha = ((height - lineHeight) / fadeRange).coerceIn(0f, 1f)
    Box(Modifier.align(Alignment.Center).width(width).height(height)
        .clip(CircleShape)
        .beaconGlassMaterial(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
        .testTag(SEARCH_COLLAPSE_TAG)
        .then(if (fraction < .001f) Modifier.clearAndSetSemantics {} else Modifier)) {
    if (miuix()) {
        top.yukonga.miuix.kmp.basic.SearchBar(
            expanded = false, onExpandedChange = {},
            insideMargin = androidx.compose.ui.unit.DpSize.Zero,
            inputField = {
                top.yukonga.miuix.kmp.basic.InputField(
                    query = value, onQueryChange = onValueChange,
                    onSearch = { onSearch() }, expanded = focused,
                    onExpandedChange = { focused = it }, label = hint,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = BeaconListText.SearchSize, lineHeight = BeaconListText.SearchLineHeight),
                    modifier = Modifier.requiredWidth(fullWidth).requiredHeight(fieldHeight)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = hint }.testTag(SEARCH_BOX_TAG)
                        .graphicsLayer {
                            alpha = textAlpha
                            renderEffect = if (hardware && textAlpha > 0f && textAlpha < 1f) {
                                val radius = BeaconUiMetrics.SearchFadeBlurRadius.toPx() * (1f - textAlpha)
                                BlurEffect(radius, radius)
                            } else null
                        },
                )
            },
        ) {}
    } else
    BasicTextField(
        value,
        onValueChange,
        singleLine = true,
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = BeaconListText.SearchSize,
                lineHeight = BeaconListText.SearchLineHeight,
            ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier =
            Modifier
                .requiredWidth(fullWidth)
                .heightIn(min = BeaconUiMetrics.SearchFieldMinHeight)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = hint }
                .testTag(SEARCH_BOX_TAG),
        decorationBox = { field ->
            Row(Modifier.fillMaxWidth().graphicsLayer {
                alpha = textAlpha
                renderEffect = if (hardware && textAlpha > 0f && textAlpha < 1f) {
                    val radius = BeaconUiMetrics.SearchFadeBlurRadius.toPx() * (1f - textAlpha)
                    BlurEffect(radius, radius)
                } else null
            }, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    BeaconIcons.Search,
                    null,
                    Modifier
                        .padding(
                            start = BeaconUiMetrics.SearchFieldLeadingInset,
                            end = BeaconUiMetrics.SearchFieldIconGap,
                        )
                        .size(BeaconUiMetrics.SearchFieldIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.weight(1f)
                        .padding(vertical = BeaconUiMetrics.SearchFieldVerticalInset)
                ) {
                    if (value.isEmpty())
                        BeaconText(
                            hint,
                            style =
                                MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = BeaconListText.SearchSize,
                                    lineHeight = BeaconListText.SearchLineHeight,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    field()
                }
                if (value.isNotEmpty())
                    Icon(
                        BeaconIcons.Close,
                        stringResource(R.string.ui_clear_search_action),
                        Modifier
                            .size(BeaconUiMetrics.SearchFieldClearSize)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = { onValueChange("") })
                            .padding(BeaconUiMetrics.SearchFieldClearInset),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else Spacer(Modifier.width(BeaconUiMetrics.SearchFieldLeadingInset))
            }
        },
    )
    }
    }
}

/**
 * When the window is short (landscape, split screen) the page title and the search field share one
 * 44dp row: the title sits on the left, the field takes the rest. The header then costs a single
 * slot instead of a title band plus a field, and search stays persistent in both orientations. Once
 * the list scrolls the title folds away and the field keeps the whole row.
 */
@Composable
fun BeaconTitleSearchRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    titleCollapsed: Boolean = false,
    scroll: androidx.compose.foundation.lazy.LazyListState? = null,
    onSearch: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = BeaconUiMetrics.SearchFieldMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = !titleCollapsed,
            enter = fadeIn(tween(BeaconMotion.StandardMs)) + expandHorizontally(tween(BeaconMotion.StandardMs)),
            exit = fadeOut(tween(BeaconMotion.QuickMs)) + shrinkHorizontally(tween(BeaconMotion.QuickMs)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BeaconText(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
            }
        }
        BeaconSearchBox(
            value,
            onValueChange,
            hint,
            Modifier.weight(1f),
            scroll = scroll,
            onSearch = onSearch,
        )
    }
}

@Composable
fun BeaconActionIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    showBadge: Boolean = false,
    onClick: () -> Unit,
) {
    BeaconActionTooltip(description) {
    BeaconIconButton(onClick = onClick, enabled = enabled) {
        BeaconBadgedBox(showBadge) {
            Icon(
                icon,
                description,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
    }
    }
}

@Composable
fun BeaconPreferenceToggle(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (miuix()) {
        top.yukonga.miuix.kmp.preference.SwitchPreference(
            title = title, summary = summary.takeIf { it.isNotBlank() },
            checked = checked, enabled = enabled, onCheckedChange = { if (enabled) onCheckedChange(it) },
            insideMargin = PaddingValues(vertical = BeaconUiMetrics.FieldVerticalInset),
            modifier = Modifier.semantics { contentDescription = title; if (!enabled) disabled() },
        )
        return
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = BeaconUiMetrics.FieldMinHeight)
            .named(title)
            .toggleable(value = checked, enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
    ) {
        Column(
            Modifier.weight(1f).alpha(if (enabled) 1f else .38f).padding(vertical = BeaconUiMetrics.FieldVerticalInset),
            verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4),
        ) {
            BeaconFittedText(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotBlank())
                BeaconFittedText(
                    summary,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        // The switch is a sibling of the label column, so it carries no text of its own and takes
        // the title as its accessible name.
        if (LocalBeaconUiStyle.current == BeaconUiStyle.MIUIX)
            top.yukonga.miuix.kmp.basic.Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
                enabled = enabled,
            )
        else
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
                enabled = enabled,
            )
    }
}

/** Smallest gap kept between the key and the value when both share one line. */
private val DIAGNOSTIC_ROW_GAP = 16.dp

/** Slack absorbed before falling back to the stacked form, covering rounding and font metrics. */
internal const val DIAGNOSTIC_ROW_TOLERANCE_PX = 12

/**
 * Whether a key and its value share one line, or must stack.
 *
 * Judged against the full width the row actually has, never a fraction of it: a fixed fractional
 * split handed the key about a third of the row whatever its length, so an 11-character key wrapped
 * in a column that could not hold it, orphaning its last character onto a line of its own.
 */
internal fun diagnosticRowFits(
    labelWidthPx: Int,
    valueWidthPx: Int,
    gapPx: Int,
    availableWidthPx: Int,
): Boolean = labelWidthPx + valueWidthPx + gapPx + DIAGNOSTIC_ROW_TOLERANCE_PX <= availableWidthPx

/** Compatibility entry point: metadata always uses the shared title/value geometry. */
@Composable
fun DiagnosticRow(label: String, value: String, reserveValueLines: Int = 0) {
    SettingsOverviewField(label, value)
}

/** Statistics always use separate cells against the page background, without a nested card. */
@Composable
fun SettingsMetricSection(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingsLabel(title, Modifier.weight(1f))
            action?.invoke()
        }
        content()
    }
}

/** Compact numeric facts, preserving readable values at large font sizes. */
@Composable
fun SettingsMetricGrid(
    metrics: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    columns: Int? = null,
) {
    require(columns == null || columns > 0)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Counts and loading placeholders must not change the number of columns.
        val minimumCell = with(density) { (80.dp * density.fontScale.coerceAtLeast(1f)).roundToPx() }
        val gapWidth = with(density) { 8.dp.roundToPx() }
        val availableColumns = ((constraints.maxWidth + gapWidth) / (minimumCell + gapWidth)).coerceAtLeast(1)
        val candidateColumns = minOf(columns ?: 2, availableColumns)
        val resolvedColumns = if (metrics.size == 3 && candidateColumns == 2) 1 else candidateColumns
        Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
            metrics.chunked(resolvedColumns).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
                ) {
                    pair.forEach { (label, value) ->
                        Surface(
                            Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                Modifier.heightIn(min = 88.dp).padding(12.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                BeaconFittedText(
                                    label,
                                    modifier = Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme.typography.labelMedium.copy(
                                            textAlign = TextAlign.Center
                                        ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                BeaconFittedText(
                                    value,
                                    modifier = Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(
                                            fontFeatureSettings = "tnum",
                                            textAlign = TextAlign.Center
                                        ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    repeat(resolvedColumns - pair.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun BeaconEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    loading: Boolean = false,
    action: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
        else
            Icon(
                icon,
                null,
                Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        BeaconText(
            title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        BeaconText(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        if (!loading) action()
    }
}

@Composable
internal fun BeaconActionTooltip(description: String, content: @Composable () -> Unit) {
    if (miuix()) top.yukonga.miuix.kmp.basic.TooltipBox(text = description, content = content)
    else androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { BeaconText(description) } },
        state = androidx.compose.material3.rememberTooltipState(),
        content = content,
    )
}
