package io.github.beacon.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.policy.tagToneIndex

/** Bright opaque labels retain the same identity and readable ink in both themes. */
internal val BeaconTagInk = Color(0xFF14263D)
internal val BeaconChoiceSelected = Color(0xFF91CEFF)
internal val BeaconChoiceUnselected = Color(0xFFDDECFF)
internal val BeaconTagOutline = Color(0xFF4089CB)
internal val BeaconTagPalette = listOf(
    Color(0xFF8ED1FF), // sky
    Color(0xFF82E6C5), // mint
    Color(0xFFC9AEFF), // lavender
    Color(0xFFFFD16F), // amber
    Color(0xFFFFAACD), // rose
    Color(0xFFB5E980), // lime
    Color(0xFF83E3ED), // cyan
    Color(0xFFFFB991), // coral
)

@Composable
@ReadOnlyComposable
internal fun beaconTagContainer(label: String, slot: Int): Color =
    if (slot < 0) BeaconChoiceUnselected else BeaconTagPalette[tagToneIndex(label, slot)]

@Composable
@ReadOnlyComposable
internal fun beaconTagContent(@Suppress("UNUSED_PARAMETER") toneSlot: Int): Color = BeaconTagInk

/** The same removable filter is used by message, device and application lists. */
@Composable
fun BeaconActiveFilterChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = BeaconUiMetrics.PillShape,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val foreground = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val ink = foreground.copy(alpha = if (enabled) 1f else .38f)
    androidx.compose.material3.InputChip(selected = selected, onClick = onClick, modifier = modifier,
        enabled = enabled, shape = shape, label = {
            androidx.compose.runtime.CompositionLocalProvider(LocalButtonLabel provides true,
                androidx.compose.material3.LocalContentColor provides ink,
                top.yukonga.miuix.kmp.theme.LocalContentColor provides ink) { label() }
        }, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        border = androidx.compose.foundation.BorderStroke(1.dp,
            androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else .38f)),
        colors = androidx.compose.material3.InputChipDefaults.inputChipColors(
            containerColor = Color.Transparent, selectedContainerColor = Color.Transparent,
            labelColor = foreground, selectedLabelColor = foreground,
            leadingIconColor = foreground, selectedLeadingIconColor = foreground,
            trailingIconColor = foreground, selectedTrailingIconColor = foreground,
            disabledContainerColor = Color.Transparent,
            disabledSelectedContainerColor = Color.Transparent,
            disabledLabelColor = ink, disabledLeadingIconColor = ink, disabledTrailingIconColor = ink,
        ))
}
