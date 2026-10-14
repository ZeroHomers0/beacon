package io.github.beacon.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle

val LocalBeaconUiStyle = staticCompositionLocalOf { BeaconUiStyle.MATERIAL3 }

@Composable
fun BeaconTheme(
    style: BeaconUiStyle,
    mode: BeaconThemeMode = BeaconThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark =
        when (mode) {
            BeaconThemeMode.SYSTEM -> isSystemInDarkTheme()
            BeaconThemeMode.LIGHT -> false
            BeaconThemeMode.DARK -> true
        }
    val context = LocalContext.current
    val colors =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) darkColorScheme() else lightColorScheme()
    SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val miuix =
        if (dark) top.yukonga.miuix.kmp.theme.darkColorScheme()
        else top.yukonga.miuix.kmp.theme.lightColorScheme()
    val bridge =
        if (style == BeaconUiStyle.MIUIX)
            colors.copy(
                primary = miuix.primary,
                onPrimary = miuix.onPrimary,
                background = miuix.background,
                onBackground = miuix.onBackground,
                surface = miuix.surface,
                onSurface = miuix.onSurface,
                primaryContainer = miuix.primaryContainer,
                onPrimaryContainer = miuix.onPrimaryContainer,
                surfaceContainerLowest = miuix.background,
                surfaceContainer = miuix.surfaceContainer,
                surfaceContainerLow = miuix.surfaceContainer,
                surfaceContainerHighest = miuix.surfaceContainerHigh,
                surfaceContainerHigh = miuix.surfaceContainerHigh,
                onSurfaceVariant = miuix.onSurfaceSecondary,
                secondaryContainer = miuix.secondaryContainer,
                onSecondaryContainer = miuix.onSecondaryContainer,
                error = miuix.error,
            )
        else colors
    CompositionLocalProvider(LocalBeaconUiStyle provides style) {
        MaterialTheme(colorScheme = bridge, typography = Typography().let { base ->
            base.copy(bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp))
        }) {
            top.yukonga.miuix.kmp.theme.MiuixTheme(colors = miuix) {
                CompositionLocalProvider(
                    top.yukonga.miuix.kmp.theme.LocalContentColor provides miuix.onBackground,
                    androidx.compose.material3.LocalContentColor provides bridge.onBackground,
                    content = content,
                )
            }
        }
    }
}
