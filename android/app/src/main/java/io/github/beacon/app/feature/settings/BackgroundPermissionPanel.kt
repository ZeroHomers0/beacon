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
import androidx.compose.ui.unit.dp
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
    var unavailable by remember { mutableStateOf(false) }
    var refreshed by remember { mutableStateOf(false) }
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiSection(stringResource(R.string.background_autostart_title)) {
            BeaconText(
                stringResource(R.string.background_manual_status),
                style = MaterialTheme.typography.titleSmall,
            )
            SettingsHint(stringResource(R.string.background_autostart_hint))
            BeaconButton(onClick = { appDetails() }, modifier = Modifier.fillMaxWidth()) {
                BeaconText(stringResource(R.string.background_open_app_settings))
            }
        }
        UiSection(stringResource(R.string.background_restriction_title)) {
            DiagnosticRow(
                stringResource(R.string.background_system_restriction),
                stringResource(
                    when (restricted) {
                        true -> R.string.background_restricted
                        false -> R.string.background_not_restricted
                        null -> R.string.background_unknown
                    }
                ),
            )
            DiagnosticRow(
                stringResource(R.string.background_battery_optimization),
                stringResource(
                    when (exempt) {
                        true -> R.string.background_exempt
                        false -> R.string.background_optimized
                        null -> R.string.background_unknown
                    }
                ),
            )
            SettingsHint(stringResource(R.string.background_restriction_hint))
            BeaconActionPair(
                dismiss = {
                    BeaconOutlinedButton(onClick = { appDetails() }) {
                        BeaconText(stringResource(R.string.background_open_app_settings))
                    }
                },
                confirm = {
                    BeaconButton(
                        onClick = {
                            openSettings(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }
                    ) {
                        BeaconText(stringResource(R.string.background_open_battery_settings))
                    }
                },
            )
        }
        if (unavailable)
            BeaconText(
                stringResource(R.string.background_settings_unavailable),
                color = MaterialTheme.colorScheme.error,
            )
        BeaconOutlinedButton(
            onClick = {
                refresh()
                refreshed = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BeaconText(stringResource(R.string.action_refresh_status))
        }
        if (refreshed) SettingsHint(resources.getString(R.string.settings_refresh_done))
    }
}
