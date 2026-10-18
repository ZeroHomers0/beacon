package io.github.beacon.app.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.LocalBeaconUiStyle

/** Finds the search field regardless of its hint text, which is user-facing copy. */
const val SEARCH_BOX_TAG = "search-box"

/** Persistent search surface; no extra card/title/submit button around a list query. */
@Composable
fun BeaconSearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit,
) {
    BasicTextField(
        value,
        onValueChange,
        singleLine = true,
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = BeaconUiMetrics.SearchFieldMinHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .semantics { contentDescription = hint }
                .testTag(SEARCH_BOX_TAG),
        decorationBox = { field ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    BeaconIcons.Search,
                    null,
                    Modifier.padding(start = 16.dp, end = 12.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.weight(1f)
                        .padding(vertical = BeaconUiMetrics.SearchFieldVerticalInset)
                ) {
                    if (value.isEmpty())
                        BeaconText(
                            hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    field()
                }
                if (value.isNotEmpty())
                    BeaconIconButton(onClick = { onValueChange("") }) {
                        Icon(BeaconIcons.Close, stringResource(R.string.ui_clear_search_action))
                    }
                else Spacer(Modifier.width(16.dp))
            }
        },
    )
}

@Composable
fun BeaconActionIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    showBadge: Boolean = false,
    onClick: () -> Unit,
) {
    BeaconIconButton(onClick = onClick, enabled = enabled) {
        BadgedBox(badge = { if (showBadge) Badge() }) {
            Icon(
                icon,
                description,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            )
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
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = BeaconUiMetrics.FieldMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
    ) {
        Column(
            Modifier.weight(1f).padding(vertical = BeaconUiMetrics.FieldVerticalInset),
            verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4),
        ) {
            BeaconText(title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotBlank())
                BeaconText(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        // The switch is a sibling of the label column, so it carries no text of its own and takes
        // the title as its accessible name.
        if (LocalBeaconUiStyle.current == BeaconUiStyle.MIUIX)
            top.yukonga.miuix.kmp.basic.Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.named(title),
                enabled = enabled,
            )
        else
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.named(title),
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

/**
 * Key and value share one line when they genuinely fit, and stack otherwise.
 *
 * A fixed fractional split squeezed long keys into a one- or two-character orphan line and left
 * short keys floating far from their value, so measure first: the two-column form is used only when
 * the pair fits with [DIAGNOSTIC_ROW_GAP] to spare; otherwise both get the full width, which never
 * wraps. The one-line form right-aligns the value, keeping the pair symmetrical.
 */
@Composable
fun DiagnosticRow(label: String, value: String, reserveValueLines: Int = 0) {
    val labelStyle = MaterialTheme.typography.bodyLarge
    val valueStyle =
        MaterialTheme.typography.bodyMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
    val labelColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val gapPx = with(LocalDensity.current) { DIAGNOSTIC_ROW_GAP.roundToPx() }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        BoxWithConstraints(
            Modifier.heightIn(min = BeaconUiMetrics.FieldMinHeight)
                .padding(vertical = BeaconUiMetrics.FieldVerticalInset),
            contentAlignment = Alignment.CenterStart,
        ) {
            val labelWidth = measurer.measure(label, labelStyle, maxLines = 1).size.width
            val valueWidth = measurer.measure(value, valueStyle, maxLines = 1).size.width
            val fits =
                reserveValueLines == 0 && constraints.hasBoundedWidth &&
                    diagnosticRowFits(
                        labelWidth,
                        valueWidth,
                        gapPx,
                        constraints.maxWidth,
                    )
            if (fits)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BeaconText(label, style = labelStyle, color = labelColor)
                    BeaconText(value, style = valueStyle)
                }
            else
                Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4)) {
                    BeaconText(label, style = labelStyle, color = labelColor)
                    BeaconText(
                        value,
                        modifier = if (reserveValueLines > 0) Modifier.heightIn(
                            min = with(LocalDensity.current) {
                                (valueStyle.lineHeight * reserveValueLines).toDp()
                            }
                        ) else Modifier,
                        style = valueStyle,
                        maxLines = if (reserveValueLines > 0) reserveValueLines else Int.MAX_VALUE,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
        }
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
    val measurer = rememberTextMeasurer()
    val widest =
        metrics.maxOfOrNull { (label, value) ->
            maxOf(
                measurer
                    .measure(label, MaterialTheme.typography.labelMedium, maxLines = 1)
                    .size
                    .width,
                measurer
                    .measure(value, MaterialTheme.typography.titleMedium, maxLines = 1)
                    .size
                    .width,
            )
        } ?: 0
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cellWidth = widest + with(density) { 24.dp.roundToPx() }
        val gapWidth = with(density) { 8.dp.roundToPx() }
        val availableColumns =
            ((constraints.maxWidth + gapWidth) / (cellWidth + gapWidth).coerceAtLeast(1))
                .coerceAtLeast(1)
        val resolvedColumns = minOf(columns ?: 2, availableColumns)
        Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
            metrics.chunked(resolvedColumns).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
                ) {
                    pair.forEach { (label, value) ->
                        Surface(
                            Modifier.weight(1f).fillMaxHeight(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            color = androidx.compose.ui.graphics.Color.Transparent,
                        ) {
                            Column(
                                Modifier.padding(if (resolvedColumns == 3) 8.dp else 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                BeaconText(
                                    label,
                                    modifier = Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme.typography.labelMedium.copy(
                                            textAlign = TextAlign.Center
                                        ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                BeaconText(
                                    value,
                                    modifier = Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(
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
