@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
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
) {
    val scrollProgress = LocalBeaconScrollProgress.current
    val isMiuix = miuix()
    val glassAlpha: () -> Float = {
        if (isMiuix) maxOf(scrollProgress(), behavior.miuix.state.collapsedFraction).coerceIn(0f, 1f) else 1f
    }
    CompositionLocalProvider(LocalBeaconToolbarGlassAlpha provides glassAlpha, LocalToolbarButtonStyle provides true) {
    val groupedActions: @Composable RowScope.() -> Unit = { BeaconToolbarActions(actions) }
    if (miuix()) {
        BeaconGlassSurface(androidx.compose.material3.MaterialTheme.colorScheme.background) {
            if (compact)
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
                title = { BeaconText(title) },
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
