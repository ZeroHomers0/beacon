@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.beacon.app.domain.model.BeaconUiStyle

/** One shared surface for trailing controls; children retain their own hit targets and semantics. */
@Composable
internal fun BeaconToolbarActions(actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.padding(end = BeaconUiMetrics.Gap8),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = actions)

}

class BeaconBarBehavior(
    val miuix: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    val material: androidx.compose.material3.TopAppBarScrollBehavior,
) {
    // Consume downward drag to expand the title before the enclosing refresh container sees it.
    private val materialTitleFirst =
        object : NestedScrollConnection by material.nestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && material.state.heightOffset < 0) {
                    val previous = material.state.heightOffset
                    material.state.heightOffset += available.y
                    return Offset(0f, material.state.heightOffset - previous)
                }
                return material.nestedScrollConnection.onPreScroll(available, source)
            }
        }

    fun connection(style: BeaconUiStyle): NestedScrollConnection =
        if (style == BeaconUiStyle.MIUIX) miuix.nestedScrollConnection else materialTitleFirst
}

@Composable
fun rememberBeaconBarBehavior(): BeaconBarBehavior {
    val miuix = top.yukonga.miuix.kmp.basic.MiuixScrollBehavior()
    val material = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    return remember(miuix, material) { BeaconBarBehavior(miuix, material) }
}

@Composable
fun BeaconTopBar(
    title: String,
    compact: Boolean,
    behavior: BeaconBarBehavior,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    prioritizeTitle: Boolean = false,
) {
    val groupedActions: @Composable RowScope.() -> Unit = { BeaconToolbarActions(actions) }
    if (miuix()) {
        BeaconSurface(androidx.compose.material3.MaterialTheme.colorScheme.background) {
            if (compact && prioritizeTitle) {
                val textStyles = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
                top.yukonga.miuix.kmp.theme.MiuixTheme(
                    colors = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme,
                    textStyles = textStyles.copy(title3 = textStyles.title3.copy(
                        fontSize = androidx.compose.material3.MaterialTheme.typography.titleMedium.fontSize)),
                ) {
                top.yukonga.miuix.kmp.basic.SmallTopAppBar(
                    title,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    navigationIcon = navigationIcon,
                    actions = groupedActions,
                    titlePadding = BeaconUiMetrics.Gap8,
                    navigationIconPadding = BeaconUiMetrics.Gap8,
                    actionIconPadding = 0.dp,
                )
                }
            } else if (compact)
                top.yukonga.miuix.kmp.basic.SmallTopAppBar(
                    title,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    navigationIcon = navigationIcon,
                    actions = groupedActions,
                )
            else
                top.yukonga.miuix.kmp.basic.TopAppBar(
                    title,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    navigationIcon = navigationIcon,
                    actions = groupedActions,
                    scrollBehavior = behavior.miuix,
                )
        }
    } else {
        if (compact)
            androidx.compose.material3.TopAppBar(
                title = {
                    if (prioritizeTitle) BeaconFittedText(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    else BeaconText(title)
                },
                navigationIcon = navigationIcon,
                actions = groupedActions,
            )
        else
            androidx.compose.material3.LargeTopAppBar(
                title = { BeaconText(title) },
                navigationIcon = navigationIcon,
                actions = groupedActions,
                scrollBehavior = behavior.material,
            )
    }
}

@Composable
fun BeaconFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val buttonModifier = modifier.semantics { this.selected = selected }
    BeaconUnifiedButton(onClick, buttonModifier, enabled, selected = selected) {
        Box(Modifier.size(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (selected) androidx.compose.material3.Icon(io.github.beacon.app.ui.icons.BeaconIcons.Check,
                null, Modifier.size(16.dp))
        }
        Spacer(Modifier.width(BeaconUiMetrics.Gap4))
        label()
    }
}
