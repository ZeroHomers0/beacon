package io.github.beacon.app.feature.settings

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.*

@Composable
fun BackgroundPermissionPanel() {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var restricted by remember { mutableStateOf<Boolean?>(null) }
    var exempt by remember { mutableStateOf<Boolean?>(null) }
    var help by remember { mutableStateOf(false) }
    if (help)
        BeaconAlertDialog(
            onDismissRequest = { help = false },
            title = { BeaconText(stringResource(R.string.settings_background_permissions_title)) },
            text = {
                SettingsHint(
                    stringResource(R.string.background_autostart_hint) +
                        "\n\n" +
                        stringResource(R.string.background_restriction_hint)
                )
            },
            confirmButton = {
                BeaconButton(onClick = { help = false }) {
                    BeaconText(stringResource(R.string.action_close))
                }
            },
        )
    var unavailable by remember { mutableStateOf(false) }
    fun refresh() {
        restricted =
            runCatching {
                if (Build.VERSION.SDK_INT >= 28)
                    context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
                else null
            }
                .getOrNull()
        exempt =
            runCatching {
                context
                    .getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(context.packageName)
            }
                .getOrNull()
    }
    fun openSettings(intent: Intent) {
        unavailable = false
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            unavailable = true
        } catch (_: SecurityException) {
            unavailable = true
        }
    }
    fun appDetails() =
        openSettings(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        )
    DisposableEffect(owner, context) {
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap),
    ) {
        SettingsGroup(stringResource(R.string.settings_background_permissions_title)) {
            SettingsRow(stringResource(R.string.background_autostart_title),
                stringResource(R.string.background_manual_status), io.github.beacon.app.ui.icons.BeaconIcons.BackgroundTasks,
                iconTint = MaterialTheme.colorScheme.onSurface, external = true, onClick = { appDetails() })
            SettingsRow(stringResource(R.string.background_system_restriction),
                stringResource(when (restricted) {
                    true -> R.string.background_restricted
                    false -> R.string.background_not_restricted
                    null -> R.string.background_unknown
                }), io.github.beacon.app.ui.icons.BeaconIcons.Diagnostics,
                iconTint = MaterialTheme.colorScheme.onSurface, external = true, onClick = { appDetails() })
            SettingsRow(stringResource(R.string.background_battery_optimization),
                stringResource(when (exempt) {
                    true -> R.string.background_exempt
                    false -> R.string.background_optimized
                    null -> R.string.background_unknown
                }), io.github.beacon.app.ui.icons.BeaconIcons.PowerSaving,
                iconTint = MaterialTheme.colorScheme.onSurface, external = true,
                onClick = { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        }
        SettingsActions {
            SettingsTextAction(stringResource(R.string.settings_background_help),
                io.github.beacon.app.ui.icons.BeaconIcons.Info) { help = true }
        }
        if (unavailable) SettingsNotice(stringResource(R.string.background_settings_unavailable))
    }
}
