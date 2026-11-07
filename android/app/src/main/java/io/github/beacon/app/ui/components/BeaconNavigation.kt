@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
fun BeaconScaffold(
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    snackbar: androidx.compose.material3.SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    // The IME overlays the window. Preserve page and navigation geometry while it opens or closes.
    Box(Modifier.fillMaxSize()) {
        if (miuix()) {
            val background = MaterialTheme.colorScheme.background
            top.yukonga.miuix.kmp.basic.Scaffold(
                topBar = topBar, containerColor = background,
                bottomBar = bottomBar, content = content,
            )
        } else
            androidx.compose.material3.Scaffold(
                topBar = topBar,
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = bottomBar,
                content = content,
            )
        if (snackbar != null)
            BeaconFeedbackHost(
                snackbar,
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 96.dp),
            )
    }
}

/** Single preference row: selected value at the right, choices in a focused popup. */
@Composable
fun BeaconDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    settingsRow: Boolean = false,
    itemLeading: (@Composable (Int) -> Unit)? = null,
    onSelected: (Int) -> Unit,
) {
    if (miuix()) {
        val entry = top.yukonga.miuix.kmp.basic.DropdownEntry(items.mapIndexed { index, label ->
            top.yukonga.miuix.kmp.basic.DropdownItem(text = label, selected = index == selectedIndex,
                enabled = enabled, onClick = { if (enabled) onSelected(index) },
                icon = itemLeading?.let { render -> { _: Modifier -> render(index) } })
        })
        top.yukonga.miuix.kmp.preference.WindowDropdownPreference(
            entry = entry, title = title,
            modifier = Modifier.semantics(mergeDescendants = true) { if (!enabled) disabled() },
            summary = items.getOrElse(selectedIndex) { BeaconUnknownValue },
            enabled = enabled, showValue = false, maxHeight = 360.dp,
            startAction = icon?.let { image -> { androidx.compose.material3.Icon(image, null) } },
            insideMargin = PaddingValues(horizontal = if (settingsRow) BeaconUiMetrics.CardInset else 0.dp,
                vertical = BeaconUiMetrics.FieldVerticalInset),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        if (settingsRow) {
            SettingsRow(
                title,
                items.getOrElse(selectedIndex) { BeaconUnknownValue },
                icon ?: BeaconIcons.Settings,
                iconTint = MaterialTheme.colorScheme.onSurface,
                enabled = enabled,
                selection = true,
                onClick = { expanded = true },
            )
        } else
            Row(
                Modifier.fillMaxWidth()
                    .alpha(if (enabled) 1f else .38f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) { expanded = true }
                    .heightIn(min = BeaconUiMetrics.FieldMinHeight)
                    .padding(vertical = BeaconUiMetrics.FieldVerticalInset),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
            ) {
                if (icon != null) androidx.compose.material3.Icon(icon, null, Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap2)) {
                    BeaconFittedText(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                    BeaconFittedText(items.getOrElse(selectedIndex) { BeaconUnknownValue }, Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Icon(BeaconIcons.Select,
                    stringResource(R.string.ui_expand_options_action), Modifier.size(18.dp))
            }
        BeaconDropdownMenu(expanded && enabled, { expanded = false }) {
            items.forEachIndexed { index, label ->
                BeaconDropdownMenuItem({ BeaconSelectableRow(label, index == selectedIndex,leading=itemLeading?.let{render->{render(index)}}) }) {
                    expanded = false
                    onSelected(index)
                }
            }
        }
    }
}
