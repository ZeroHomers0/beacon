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
