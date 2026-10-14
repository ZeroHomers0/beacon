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
    if (miuix()) {
        if (compact)
            top.yukonga.miuix.kmp.basic.SmallTopAppBar(
                title,
                navigationIcon = navigationIcon,
                actions = actions,
            )
        else
            top.yukonga.miuix.kmp.basic.TopAppBar(
                title,
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = behavior.miuix,
            )
    } else {
        if (compact)
            androidx.compose.material3.TopAppBar(
                title = { BeaconText(title) },
                navigationIcon = navigationIcon,
                actions = actions,
            )
        else
            androidx.compose.material3.LargeTopAppBar(
                title = { BeaconText(title) },
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = behavior.material,
            )
    }
}

@Composable
fun BeaconFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (miuix())
        top.yukonga.miuix.kmp.basic.Button(
            onClick,
            modifier.semantics { this.selected = selected },
            enabled = enabled,
            minHeight = 48.dp,
            colors =
                if (selected) top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary()
                else top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(),
        ) {
            val color =
                if (selected) top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onPrimary
                else top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSecondaryVariant
            CompositionLocalProvider(
                top.yukonga.miuix.kmp.theme.LocalContentColor provides color,
                androidx.compose.material3.LocalContentColor provides color,
            ) {
                label()
            }
        }
    else androidx.compose.material3.FilterChip(selected, onClick, label, modifier = modifier, enabled = enabled)
}
