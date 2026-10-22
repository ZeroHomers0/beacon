package io.github.beacon.app.feature.settings

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.NotificationChannelKind
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
fun NotificationChannelsPanel() {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        revision++
    }
    SettingsGroup(stringResource(R.string.notification_channels)) {
        if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
            BeaconText(stringResource(R.string.channel_app_permission_off), Modifier.padding(16.dp))
        NotificationChannelKind.entries.forEach { kind ->
            val channel =
                remember(kind, revision) {
                    context
                        .getSystemService(NotificationManager::class.java)
                        .getNotificationChannel(kind.id)
                }
            val title =
                when (kind) {
                    NotificationChannelKind.FRESH -> stringResource(R.string.channel_fresh)
                    NotificationChannelKind.HISTORY -> stringResource(R.string.channel_history)
                    NotificationChannelKind.RUNTIME -> stringResource(R.string.channel_runtime)
                    NotificationChannelKind.IMPORTANT -> stringResource(R.string.channel_important)
                }
            val summary =
                when (channel?.importance) {
                    null -> stringResource(R.string.channel_missing)
                    NotificationManager.IMPORTANCE_NONE -> stringResource(R.string.channel_disabled)
                    NotificationManager.IMPORTANCE_LOW,
                    NotificationManager.IMPORTANCE_MIN -> stringResource(R.string.channel_silent)
                    else -> stringResource(R.string.channel_enabled)
                }
            SettingsRow(
                title,
                summary,
                when (kind) {
                    // Distinct within the list: the row title carries the meaning, so a repeated
                    // icon would read as a copy-paste mistake.
                    NotificationChannelKind.FRESH -> BeaconIcons.Mail
                    NotificationChannelKind.HISTORY -> BeaconIcons.QuietHours
                    NotificationChannelKind.RUNTIME -> BeaconIcons.Realtime
                    NotificationChannelKind.IMPORTANT -> BeaconIcons.Messages
                },
                iconTint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                external = true,
                onClick = {
                failed = runCatching {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    if (manager.getNotificationChannel(kind.id) == null &&
                        kind in setOf(NotificationChannelKind.FRESH, NotificationChannelKind.IMPORTANT)) {
                        val important = kind == NotificationChannelKind.IMPORTANT
                        manager.createNotificationChannel(android.app.NotificationChannel(kind.id, title,
                            if (important) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT).apply {
                            description = resources.getString(if (important) R.string.channel_important_desc else R.string.channel_fresh_desc)
                        })
                        revision++
                    }
                    val intent = if (manager.getNotificationChannel(kind.id) == null)
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, kind.id)
                    context.startActivity(intent)
                }.isFailure
            })
        }
        if (failed)
            BeaconText(
                stringResource(R.string.notification_channel_failed),
                Modifier.padding(16.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            )
    }
}
