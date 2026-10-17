package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    var pendingSave by remember { mutableStateOf<CleanupSettings?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.runtimeConnected, attempt) {
        settingsLoaded = false
        loadFailed = false
        saving = false
        if (pendingSave == null) saveFailed = false
        if (state.runtimeConnected)
            actions.cleanupSettings(
                null,
                onFailure = { loadFailed = true },
            ) {
                settings = it
                settingsLoaded = true
                if (pendingSave != null) {
                    loadFailed = true
                    saveFailed = true
                }
            }
    }
    fun save(value: CleanupSettings, onSaved: () -> Unit = {}) {
        if (saving || !settingsLoaded || !state.runtimeConnected || state.messageBusy) return
        pendingSave = value
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
            pendingSave = null
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
                    stringResource(
                        if (saveFailed && settingsLoaded) R.string.settings_retry_save
                        else R.string.settings_reload
                    ),
                    io.github.beacon.app.ui.icons.BeaconIcons.History,
                    Modifier.fillMaxWidth(),
                    !state.messageBusy && state.runtimeConnected,
                ) {
                    val pending = pendingSave
                    if (saveFailed && settingsLoaded && pending != null) save(pending)
                    else attempt++
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
                        formatStorageSize(context, it)
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
                            resources.getString(R.string.settings_pending_notifications_title),
                            preview?.pendingNotifications?.toString() ?: "—",
                        ),
                    ),
                    columns = 3,
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
                        ).ifBlank { "—" },
                        reserveValueLines = 2,
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
            SettingsPreferenceActions {
                BeaconActionButton(
                    stringResource(R.string.storage_custom),
                    io.github.beacon.app.ui.icons.BeaconIcons.Tune,
                    Modifier.fillMaxWidth(),
                    !state.messageBusy && settingsLoaded && !saving,
                ) {
                    custom = true
                }
            }
        }
        SettingsSection(stringResource(R.string.storage_preview_title)) {
            SettingsMetricGrid(
                listOf(
                    stringResource(R.string.settings_cleanup_eligible) to
                        (preview?.eligible?.toString() ?: "—"),
                    stringResource(R.string.settings_cleanup_protected) to
                        (preview?.protected?.toString() ?: "—"),
                    stringResource(R.string.settings_cleanup_planned) to
                        (preview?.planned?.toString() ?: "—"),
                ),
                columns = 3,
            )
            SettingsHint(
                stringResource(
                    when {
                        !state.runtimeConnected -> R.string.settings_runtime_disconnected
                        state.messageBusy || (!settingsLoaded && !loadFailed) ->
                            R.string.settings_data_loading
                        preview == null || !state.cleanupPreviewFresh -> R.string.settings_preview_unavailable
                        preview.planned <= 0 -> R.string.settings_preview_empty
                        else -> R.string.settings_preview_ready
                    }
                )
            )
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
                        enabled =
                            state.runtimeConnected &&
                                !state.messageBusy &&
                                state.cleanupPreviewFresh &&
                                (preview?.planned ?: 0) > 0,
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
            enabled = !state.messageBusy && settingsLoaded && !saving && state.cleanupPreviewFresh && preview?.token == snapshot.token,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                actions.applyCleanup(snapshot.token)
            },
        )
    }
}
