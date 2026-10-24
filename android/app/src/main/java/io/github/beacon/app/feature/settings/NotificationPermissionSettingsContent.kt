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
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.components.BeaconActionButton
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconUiMetrics
import io.github.beacon.app.ui.components.NotificationPermissionState
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsHint
import io.github.beacon.app.ui.components.SettingsRow
import io.github.beacon.app.ui.components.SettingsSection
import io.github.beacon.app.ui.components.SettingsStatusSummary
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
    connected: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    loading: Boolean = false,
) {
    item {
        SettingsGroup(stringResource(R.string.settings_permission_status_heading)) {
            val allowed = notificationPermission == NotificationPermissionState.ALLOWED
            SettingsRow(
                title = stringResource(R.string.settings_system_notification_title),
                subtitle = stringResource(when (notificationPermission) {
                    NotificationPermissionState.UNKNOWN -> R.string.diag_not_read_label
                    NotificationPermissionState.ALLOWED -> R.string.settings_notifications_allowed_label
                    NotificationPermissionState.DENIED -> R.string.settings_notifications_denied_label
                }),
                icon = BeaconIcons.Messages,
                iconTint = if (allowed) io.github.beacon.app.ui.theme.beaconSuccessColor()
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                external = allowed || Build.VERSION.SDK_INT < 33,
                onClick = {
                    if (!allowed && notificationPermission == NotificationPermissionState.DENIED && Build.VERSION.SDK_INT >= 33) {
                        onRequestNotificationPermission()
                    } else try {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    } catch (_: android.content.ActivityNotFoundException) { onNotificationSettingsUnavailable() }
                    catch (_: SecurityException) { onNotificationSettingsUnavailable() }
                },
            )
        }
    }
    item {
        // Resending is an action, not navigation, so it carries a button instead of the arrow the
        // row above uses to leave the app.
        SettingsSection(stringResource(R.string.settings_pending_notifications_title), action = {
            BeaconRefreshAction(stringResource(R.string.action_refresh_status), connected && !busy && !loading,
                loading, onClick = onRetry)
        }) {
            BeaconFittedText(
                (pendingReminderCount?.let {
                    if (it > 0) resources.getString(R.string.settings_reminders_pending, it)
                    else resources.getString(R.string.settings_reminders_empty)
                }
                    ?: resources.getString(
                        when {
                            !connected -> R.string.settings_runtime_disconnected
                            loadFailed -> R.string.settings_reminders_failed
                            else -> R.string.settings_reminders_loading
                        }
                    )),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsActions {
                SettingsPrimaryButton(
                    stringResource(R.string.settings_reminders_send),
                    enabled = connected && !busy && (pendingReminderCount ?: 0) > 0) {
                        actions.transport(notifyOnly = true)
                    }
            }
        }
    }

    item { NotificationChannelsPanel() }
}
