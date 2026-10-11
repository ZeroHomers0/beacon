@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconActionButton
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.NotificationPermissionState
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsHint
import io.github.beacon.app.ui.components.SettingsRow
import io.github.beacon.app.ui.components.UiSection
import io.github.beacon.app.ui.icons.BeaconIcons

internal fun LazyListScope.notificationPermissionSettingsItems(
    resources: android.content.res.Resources,
    actions: RuntimeActions,
    notificationPermission: NotificationPermissionState,
    pendingReminderCount: Long?,
    onRequestNotificationPermission: () -> Unit,
    onNotificationSettingsUnavailable: () -> Unit,
    busy: Boolean,
    context: Context,
) {
    item {
        UiSection(stringResource(R.string.settings_notifications_section_title)) {
            val allowed = notificationPermission == NotificationPermissionState.ALLOWED
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (allowed) BeaconIcons.Check else BeaconIcons.Warning,
                    null,
                    tint =
                        if (allowed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                )
                Column(Modifier.weight(1f)) {
                    BeaconText(
                        if (notificationPermission == NotificationPermissionState.UNKNOWN)
                            stringResource(R.string.diag_not_read_label)
                        else if (allowed)
                            stringResource(R.string.settings_notifications_allowed_label)
                        else stringResource(R.string.settings_notifications_denied_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (notificationPermission == NotificationPermissionState.DENIED)
                BeaconButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) onRequestNotificationPermission()
                        else {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(
                                            Settings.EXTRA_APP_PACKAGE,
                                            context.packageName,
                                        )
                                )
                            } catch (_: android.content.ActivityNotFoundException) {
                                onNotificationSettingsUnavailable()
                            } catch (_: SecurityException) {
                                onNotificationSettingsUnavailable()
                            }
                        }
                    },
                ) {
                    BeaconText(
                        stringResource(
                            if (Build.VERSION.SDK_INT >= 33)
                                R.string.settings_allow_notifications_action
                            else R.string.settings_system_notification_title
                        )
                    )
                }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.settings_notification_settings_title)) {
            SettingsRow(
                stringResource(R.string.settings_system_notification_title),
                stringResource(R.string.settings_notification_channels_desc),
                BeaconIcons.Settings,
            ) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    onNotificationSettingsUnavailable()
                }
            }
        }
    }
    item {
        // Resending is an action, not navigation, so it carries a button instead of the arrow the
        // row above uses to leave the app.
        UiSection(stringResource(R.string.settings_reminders_send)) {
            SettingsHint(
                (pendingReminderCount?.let {
                    if (it > 0) resources.getString(R.string.settings_reminders_pending, it)
                    else resources.getString(R.string.settings_reminders_empty)
                } ?: resources.getString(R.string.settings_reminders_loading)) +
                    " " +
                    stringResource(R.string.settings_process_read_hint)
            )
            BeaconActionButton(
                stringResource(R.string.settings_reminders_send),
                BeaconIcons.DoneAll,
                Modifier.fillMaxWidth(),
                !busy && (pendingReminderCount ?: 0) > 0,
            ) {
                actions.transport(notifyOnly = true)
            }
        }
    }
    item { NotificationChannelsPanel() }
}
