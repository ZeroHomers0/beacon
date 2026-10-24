@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem as MaterialNavigationItem
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
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
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = bottomBar,
                content = content,
            )
        else
            androidx.compose.material3.Scaffold(
                topBar = topBar,
                containerColor = MaterialTheme.colorScheme.background,
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
    val icons = listOf(BeaconIcons.Messages, BeaconIcons.Settings, BeaconIcons.Messages)
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
                titleRes.indices.sortedBy{when(it){0->0;2->1;else->2}}.forEach { index ->
                val res=titleRes[index]
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
            titleRes.indices.sortedBy{when(it){0->0;2->1;else->2}}.forEach { index ->
                val res=titleRes[index]
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
            titleRes.indices.sortedBy{when(it){0->0;2->1;else->2}}.forEach { index ->
                val res=titleRes[index]
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
            titleRes.indices.sortedBy{when(it){0->0;2->1;else->2}}.forEach { index ->
                val res=titleRes[index]
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
    itemLeading: (@Composable (Int) -> Unit)? = null,
    onSelected: (Int) -> Unit,
) {
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
