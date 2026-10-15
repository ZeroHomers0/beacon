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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle

val LocalBeaconUiStyle = staticCompositionLocalOf { BeaconUiStyle.MATERIAL3 }

/** Success is legible in both palettes and reserved for a verified positive state. */
@Composable
fun beaconSuccessColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF6DDBA8)
    else Color(0xFF16754D)

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
        } else if (dark) darkColorScheme(
            primary = Color(0xFFA9C7FF),
            primaryContainer = Color(0xFF173B70),
            onPrimaryContainer = Color(0xFFD5E3FF),
            background = Color(0xFF111318),
            surface = Color(0xFF191C22),
            surfaceContainerLow = Color(0xFF191C22),
        ) else lightColorScheme(
            primary = Color(0xFF3478F6),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEAF2FF),
            onPrimaryContainer = Color(0xFF173B70),
            background = Color(0xFFF6F7FA),
            surface = Color.White,
            surfaceContainerLow = Color.White,
            onBackground = Color(0xFF202633),
            onSurface = Color(0xFF202633),
            onSurfaceVariant = Color(0xFF737E90),
            outlineVariant = Color(0xFFE9EDF2),
            error = Color(0xFFE5484D),
        )
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
            base.copy(
                headlineLarge = base.headlineLarge.copy(fontSize = 28.sp, lineHeight = 36.sp),
                headlineSmall = base.headlineSmall.copy(fontSize = 22.sp, lineHeight = 30.sp),
                bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
            )
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
