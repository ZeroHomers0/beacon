@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem as MaterialNavigationItem
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.ui.icons.BeaconIcons
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationItem

@Composable
fun BeaconScaffold(
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    snackbar: androidx.compose.material3.SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    // The IME overlays the window. Preserve page and navigation geometry while it opens or closes.
    Box(Modifier.fillMaxSize()) {
        if (miuix())
            top.yukonga.miuix.kmp.basic.Scaffold(
                topBar = topBar,
                bottomBar = bottomBar,
                content = content,
            )
        else
            androidx.compose.material3.Scaffold(
                topBar = topBar,
                bottomBar = bottomBar,
                content = content,
            )
        if (snackbar != null)
            androidx.compose.material3.SnackbarHost(
                snackbar,
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 96.dp),
            ) { data ->
                BeaconOperationSnackbar(data)
            }
    }
}

@Composable
fun BeaconNavigation(
    page: Int,
    titleRes: List<Int>,
    rail: Boolean = false,
    onNavigate: (Int) -> Unit,
) {
    val icons = listOf(BeaconIcons.Messages, BeaconIcons.Settings)
    if (rail) {
        if (miuix()) {
            val railColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
            top.yukonga.miuix.kmp.basic.NavigationRail(
                color = railColor,
                modifier =
                    Modifier.fillMaxHeight()
                        .background(railColor)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Start +
                                    WindowInsetsSides.Top +
                                    WindowInsetsSides.Bottom
                            )
                        ),
            ) {
                titleRes.forEachIndexed { index, res ->
                    top.yukonga.miuix.kmp.basic.NavigationRailItem(
                        page == index,
                        { onNavigate(index) },
                        icons[index],
                        stringResource(res),
                    )
                }
            }
            return
        }
        androidx.compose.material3.NavigationRail(
            Modifier.fillMaxHeight()
                .width(96.dp)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom
                    )
                )
        ) {
            Spacer(Modifier.height(24.dp))
            titleRes.forEachIndexed { index, res ->
                androidx.compose.material3.NavigationRailItem(
                    selected = page == index,
                    onClick = { onNavigate(index) },
                    icon = { androidx.compose.material3.Icon(icons[index], null) },
                    label = { BeaconText(stringResource(res)) },
                )
            }
        }
        return
    }
    if (miuix())
        top.yukonga.miuix.kmp.basic.NavigationBar {
            titleRes.forEachIndexed { index, res ->
                MiuixNavigationItem(
                    page == index,
                    { onNavigate(index) },
                    icons[index],
                    stringResource(res),
                )
            }
        }
    else
        androidx.compose.material3.NavigationBar {
            titleRes.forEachIndexed { index, res ->
                MaterialNavigationItem(
                    page == index,
                    onClick = { onNavigate(index) },
                    icon = { androidx.compose.material3.Icon(icons[index], null) },
                    label = { BeaconText(stringResource(res)) },
                )
            }
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
    stacked: Boolean = false,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth()) {
        if (settingsRow) {
            SettingsRow(
                title,
                items.getOrElse(selectedIndex) { "" },
                icon ?: BeaconIcons.Settings,
            ) {
                if (enabled) expanded = true
            }
        } else
            BoxWithConstraints(
                Modifier.fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .clickable(
                        enabled = enabled,
                        role = androidx.compose.ui.semantics.Role.Button,
                    ) {
                        expanded = true
                    }
                    .heightIn(min = 56.dp)
                    .padding(vertical = 12.dp),
                contentAlignment = androidx.compose.ui.Alignment.CenterStart,
            ) {
                val value = items.getOrElse(selectedIndex) { "" }
                val titleWidth =
                    measurer
                        .measure(title, MaterialTheme.typography.bodyLarge, maxLines = 1)
                        .size
                        .width
                val valueWidth =
                    measurer
                        .measure(value, MaterialTheme.typography.bodyMedium, maxLines = 1)
                        .size
                        .width
                val chromeWidth = with(density) { (if (icon == null) 30.dp else 62.dp).roundToPx() }
                val gap = with(density) { 12.dp.roundToPx() }
                val useStacked =
                    stacked ||
                        !diagnosticRowFits(
                            titleWidth,
                            valueWidth,
                            gap,
                            constraints.maxWidth - chromeWidth,
                        )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (icon != null)
                        androidx.compose.material3.Icon(
                            icon,
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    if (useStacked) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            BeaconText(title, style = MaterialTheme.typography.bodyLarge)
                            BeaconText(
                                items.getOrElse(selectedIndex) { "" },
                                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        BeaconText(
                            title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        BeaconText(
                            value,
                            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    androidx.compose.material3.Icon(
                        BeaconIcons.Arrow,
                        stringResource(R.string.ui_expand_options_action),
                        Modifier.size(18.dp),
                    )
                }
            }
        BeaconDropdownMenu(expanded && enabled, { expanded = false }) {
            items.forEachIndexed { index, label ->
                BeaconDropdownMenuItem({ BeaconSelectableRow(label, index == selectedIndex) }) {
                    expanded = false
                    onSelected(index)
                }
            }
        }
    }
}
