package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.CleanupPreview
import io.github.beacon.app.domain.model.CleanupSettings
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*

@Composable
internal fun StorageSettingsPanel(state: RuntimeState, actions: RuntimeActions) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current
    var settings by remember { mutableStateOf(CleanupSettings()) }
    val policy = settings.policy
    var settingsLoaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.runtimeConnected, attempt) {
        settingsLoaded = false
        loadFailed = false
        saving = false
        saveFailed = false
        if (state.runtimeConnected)
            actions.cleanupSettings(
                null,
                onFailure = { loadFailed = true },
            ) {
                settings = it
                settingsLoaded = true
            }
    }
    fun save(value: CleanupSettings, onSaved: () -> Unit = {}) {
        if (saving || !settingsLoaded || !state.runtimeConnected || state.messageBusy) return
        saving = true
        loadFailed = false
        saveFailed = false
        actions.cleanupSettings(
            value,
            onFailure = {
                loadFailed = true
                saving = false
                saveFailed = true
            },
        ) {
            settings = it
            saving = false
            onSaved()
        }
    }
    var confirmation by remember { mutableStateOf<CleanupPreview?>(null) }
    var clearAllStage by remember { mutableIntStateOf(0) }
    var custom by remember { mutableStateOf(false) }
    val days = (listOf(7, 30, 90, 0) + policy.days).distinct()
    val limits = (listOf(1000, 10000, 100000) + policy.maxMessages).distinct()
    val preview = state.cleanupPreview.takeIf { state.cleanupPolicy == policy }
    LaunchedEffect(policy, state.runtimeConnected, settingsLoaded) {
        confirmation = null
        if (state.runtimeConnected && settingsLoaded) actions.previewCleanup(policy)
    }
    Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)) {
        // Without this the page rendered every control disabled with no explanation; a failed read
        // is now visible and recoverable instead of looking like a broken toggle.
        if (loadFailed && !custom) {
            SettingsSection(
                stringResource(
                    if (saveFailed) R.string.storage_save_failed_title
                    else R.string.storage_load_failed_title
                )
            ) {
                BeaconText(state.status.text, color = MaterialTheme.colorScheme.error)
                BeaconActionButton(
                    stringResource(R.string.action_retry),
                    io.github.beacon.app.ui.icons.BeaconIcons.History,
                    Modifier.fillMaxWidth(),
                    !state.messageBusy && state.runtimeConnected,
                ) {
                    attempt++
                }
            }
        }
        SettingsGroup(stringResource(R.string.storage_local_database)) {
            Column(
                Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset),
                verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap),
            ) {
                BeaconText(
                    preview?.databaseBytes?.let {
                        android.text.format.Formatter.formatFileSize(context, it)
                    } ?: "—",
                    style = MaterialTheme.typography.headlineLarge,
                )
                SettingsMetricGrid(
                    listOf(
                        Pair(
                            resources.getString(R.string.ui_nav_messages_title),
                            preview?.total?.toString() ?: "—",
                        ),
                        Pair(
                            resources.getString(R.string.msg_unread_label),
                            preview?.unread?.toString() ?: "—",
                        ),
                        Pair(
                            resources.getString(R.string.settings_storage_receipts),
                            preview?.pending?.toString() ?: "—",
                        ),
                    ),
                    columns = 3,
                )
                SettingsFieldDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val pendingNotifications =
                        preview?.pendingNotifications?.let {
                            resources.getString(R.string.settings_cleanup_count, it)
                        } ?: "—"
                    val walSize =
                        preview?.walBytes?.let {
                            android.text.format.Formatter.formatFileSize(context, it)
                        } ?: "—"
                    BeaconText(
                        resources.getString(
                            R.string.storage_pending_wal_summary,
                            pendingNotifications,
                            walSize,
                        ),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                SettingsFieldDivider()
                BeaconActionButton(
                    stringResource(R.string.storage_wal_clear),
                    io.github.beacon.app.ui.icons.BeaconIcons.Storage,
                    Modifier.fillMaxWidth(),
                    state.runtimeConnected &&
                        !state.messageBusy &&
                        preview != null &&
                        preview.walBytes > 0,
                    actions::checkpointStorage,
                )
            }
        }
        SettingsPreferences(stringResource(R.string.settings_cleanup_auto_title)) {
            SettingsFieldGroup(
                {
                    BeaconPreferenceToggle(
                        resources.getString(R.string.settings_cleanup_auto_toggle),
                        "",
                        settings.automatic,
                        enabled = settingsLoaded && !state.messageBusy && !saving,
                    ) {
                        save(settings.copy(automatic = it))
                    }
                },
                {
                    DiagnosticRow(
                        stringResource(R.string.storage_last_result_label),
                        io.github.beacon.app.domain.policy.CleanupResultPresentation.compact(
                            settings.lastResult
                        ),
                    )
                },
            )
        }
        SettingsPreferences(stringResource(R.string.storage_rules_title)) {
            SettingsFieldGroup(
                {
                    BeaconDropdownPreference(
                        stringResource(R.string.storage_retention),
                        days.map {
                            if (it == 0) resources.getString(R.string.settings_cleanup_forever)
                            else resources.getString(R.string.settings_cleanup_days, it)
                        },
                        days.indexOf(policy.days),
                        enabled = !state.messageBusy && settingsLoaded && !saving,
                    ) {
                        save(settings.copy(policy = policy.copy(days = days[it])))
                    }
                },
                {
                    BeaconDropdownPreference(
                        stringResource(R.string.storage_max_messages),
                        limits.map { resources.getString(R.string.settings_cleanup_count, it) },
                        limits.indexOf(policy.maxMessages),
                        enabled = !state.messageBusy && settingsLoaded && !saving,
                    ) {
                        save(settings.copy(policy = policy.copy(maxMessages = limits[it])))
                    }
                },
                {
                    BeaconPreferenceToggle(
                        stringResource(R.string.storage_keep_unread),
                        "",
                        policy.keepUnread,
                        enabled = !state.messageBusy && settingsLoaded && !saving,
                    ) {
                        save(settings.copy(policy = policy.copy(keepUnread = it)))
                    }
                },
            )
            BeaconActionButton(
                stringResource(R.string.storage_custom),
                io.github.beacon.app.ui.icons.BeaconIcons.Tune,
                Modifier.fillMaxWidth(),
                !state.messageBusy && settingsLoaded && !saving,
            ) {
                custom = true
            }
        }
        SettingsSection(stringResource(R.string.storage_preview_title)) {
            if (preview != null) {
                SettingsMetricGrid(
                    listOf(
                        stringResource(R.string.settings_cleanup_eligible) to
                            preview.eligible.toString(),
                        stringResource(R.string.settings_cleanup_protected) to
                            preview.protected.toString(),
                        stringResource(R.string.settings_cleanup_planned) to
                            preview.planned.toString(),
                    ),
                    columns = 3,
                )
            }
            BeaconActionPair(
                {
                    BeaconOutlinedButton(
                        onClick = { actions.previewCleanup(policy) },
                        enabled = !state.messageBusy && state.runtimeConnected,
                    ) {
                        BeaconText(stringResource(R.string.storage_preview_refresh))
                    }
                },
                {
                    BeaconButton(
                        onClick = { confirmation = preview },
                        enabled = !state.messageBusy && (preview?.planned ?: 0) > 0,
                    ) {
                        BeaconText(stringResource(R.string.storage_clean_action))
                    }
                },
            )
        }
        SettingsSection(stringResource(R.string.settings_danger_section_title)) {
            BeaconDangerButton(
                onClick = { clearAllStage = 1 },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.runtimeConnected && !state.messageBusy,
            ) {
                BeaconText(stringResource(R.string.settings_cleanup_all_title))
            }
        }
    }
    if (clearAllStage > 0)
        BeaconAlertDialog(
            onDismissRequest = { clearAllStage = 0 },
            title = {
                BeaconText(
                    if (clearAllStage == 1)
                        resources.getString(R.string.settings_cleanup_all_confirm)
                    else resources.getString(R.string.settings_cleanup_all_final)
                )
            },
            text = {
                BeaconText(
                    if (clearAllStage == 1)
                        resources.getString(R.string.settings_cleanup_all_warning)
                    else resources.getString(R.string.settings_cleanup_all_final_warning)
                )
            },
            confirmButton = {
                BeaconDangerButton(
                    onClick = {
                        if (clearAllStage == 1) clearAllStage = 2
                        else {
                            clearAllStage = 0
                            actions.clearAllMessages()
                        }
                    },
                    enabled = !state.messageBusy,
                ) {
                    BeaconText(
                        if (clearAllStage == 1)
                            resources.getString(R.string.settings_cleanup_all_continue)
                        else resources.getString(R.string.settings_cleanup_all_apply)
                    )
                }
            },
            dismissButton = {
                BeaconCancelButton(onClick = { clearAllStage = 0 })
            },
        )
    if (custom)
        CustomCleanupDialog(
            policy,
            state.messageBusy || saving || !settingsLoaded || !state.runtimeConnected,
            { custom = false },
            onSave = { value -> save(settings.copy(policy = value)) { custom = false } },
            operationError = if (saveFailed) state.status.text else "",
        )
    confirmation?.let { snapshot ->
        BeaconConfirmDialog(
            title = stringResource(R.string.storage_confirm_title),
            description = stringResource(R.string.storage_confirm_message, snapshot.planned),
            confirmText = stringResource(R.string.storage_clean_action),
            danger = false,
            enabled = !state.messageBusy && settingsLoaded && !saving,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                actions.applyCleanup(snapshot.token)
            },
        )
    }
}
