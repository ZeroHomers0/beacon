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
import io.github.beacon.app.domain.model.AsyncPageState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*

@Composable
internal fun StorageSettingsPanel(
    state: RuntimeState, actions: RuntimeActions,
    cached: StorageSettingsState = remember { StorageSettingsState() },
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current
    var settings by remember { mutableStateOf(cached.snapshot ?: CleanupSettings()) }
    val policy = settings.policy
    var settingsLoaded by remember { mutableStateOf(cached.snapshot != null) }
    var settingsLoading by remember { mutableStateOf(state.runtimeConnected) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<CleanupSettings?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.runtimeConnected, attempt) {
        settingsLoading = state.runtimeConnected
        loadFailed = false
        saving = false
        if (pendingSave == null) saveFailed = false
        if (state.runtimeConnected)
            actions.cleanupSettings(
                null,
                onFailure = { loadFailed = true; settingsLoading = false },
            ) {
                settings = it
                cached.snapshot = it
                settingsLoaded = true
                settingsLoading = false
                if (pendingSave != null) {
                    loadFailed = true
                    saveFailed = true
                }
            }
    }
    val pageState = AsyncPageState.resolve(settingsLoaded, settingsLoading, loadFailed)
    val editable = pageState.hasContent && !settingsLoading && !state.messageBusy && !saving && state.runtimeConnected
    fun save(value: CleanupSettings, onSaved: () -> Unit = {}) {
        if (saving || settingsLoading || !pageState.hasContent || !state.runtimeConnected || state.messageBusy) return
        pendingSave = value
        saving = true
        loadFailed = false
        saveFailed = false
        actions.cleanupSettings(
            value,
            onFailure = {
                loadFailed = false
                saving = false
                saveFailed = true
            },
        ) {
            settings = it
            cached.snapshot = it
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
        if ((loadFailed || saveFailed) && !custom) {
            SettingsRetryFeedback(
                title = stringResource(if (saveFailed) R.string.storage_save_failed_title else R.string.storage_load_failed_title),
                failed = true,
                onRetry = if (!state.messageBusy && state.runtimeConnected) {
                    {
                        val pending = pendingSave
                        if (saveFailed && settingsLoaded && pending != null) save(pending)
                        else attempt++
                    }
                } else null,
            )
        }

        SettingsMetricSection(stringResource(R.string.storage_local_database)) {
                SettingsMetricGrid(
                    listOf(stringResource(R.string.storage_local_database) to
                        (preview?.databaseBytes?.let { formatStorageSize(context, it) } ?: BeaconUnknownValue)),
                    columns = 1,
                )
                SettingsMetricGrid(
                    listOf(
                        Pair(
                            resources.getString(R.string.ui_nav_messages_title),
                            formatCount(preview?.total),
                        ),
                        Pair(
                            resources.getString(R.string.msg_unread_label),
                            formatCount(preview?.unread),
                        ),
                        Pair(
                            resources.getString(R.string.settings_pending_notifications_title),
                            formatCount(preview?.pendingNotifications),
                        ),
                    ),
                    columns = 3,
                )
        }
        SettingsPreferences(stringResource(R.string.settings_cleanup_auto_title)) {
            SettingsFieldGroup(
                {
                    BeaconPreferenceToggle(
                        resources.getString(R.string.settings_cleanup_auto_toggle),
                        "",
                        settings.automatic,
                        enabled = editable,
                    ) {
                        save(settings.copy(automatic = it))
                    }
                },
                {
                    val result = if (settingsLoaded)
                        io.github.beacon.app.domain.policy.CleanupResultPresentation.compact(settings.lastResult)
                    else BeaconUnknownValue
                    // Keep the same record slot for initial loading, disabled and enabled states.
                    DiagnosticRow(
                        stringResource(R.string.storage_last_result_label), result.ifBlank { BeaconUnknownValue },
                        reserveValueLines = 2)

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
                        enabled = editable,
                    ) {
                        save(settings.copy(policy = policy.copy(days = days[it])))
                    }
                },
                {
                    BeaconDropdownPreference(
                        stringResource(R.string.storage_max_messages),
                        limits.map { resources.getString(R.string.settings_cleanup_count, it) },
                        limits.indexOf(policy.maxMessages),
                        enabled = editable,
                    ) {
                        save(settings.copy(policy = policy.copy(maxMessages = limits[it])))
                    }
                },
                {
                    BeaconPreferenceToggle(
                        stringResource(R.string.storage_keep_unread),
                        "",
                        policy.keepUnread,
                        enabled = editable,
                    ) {
                        save(settings.copy(policy = policy.copy(keepUnread = it)))
                    }
                },
            )
            SettingsActions {
                SettingsTextAction(stringResource(R.string.storage_custom),
                    io.github.beacon.app.ui.icons.BeaconIcons.Tune, enabled = editable) { custom = true }
            }
            SettingsHint(stringResource(R.string.settings_saved_automatically))
        }

        SettingsMetricSection(stringResource(R.string.storage_preview_title), action = {
            BeaconRefreshAction(stringResource(R.string.storage_preview_refresh), state.runtimeConnected && !state.messageBusy,
                state.messageBusy, onClick = { actions.previewCleanup(policy) })
        }) {
            SettingsMetricGrid(
                listOf(
                    stringResource(R.string.settings_cleanup_eligible) to
                        formatCount(preview?.eligible),
                    stringResource(R.string.settings_cleanup_protected) to
                        formatCount(preview?.protected),
                    stringResource(R.string.settings_cleanup_planned) to
                        formatCount(preview?.planned),
                ),
                columns = 3,
            )
            BeaconFittedText(
                stringResource(
                    when {
                        !state.runtimeConnected -> R.string.settings_runtime_disconnected
                        preview == null && (state.messageBusy || (!settingsLoaded && !loadFailed)) ->
                            R.string.settings_data_loading
                        preview == null -> R.string.settings_preview_unavailable
                        preview.planned <= 0 -> R.string.settings_preview_empty
                        else -> R.string.settings_preview_ready
                    }
                ), modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsActions {
                BeaconButton(onClick = { confirmation = preview }, modifier = Modifier.fillMaxWidth(), enabled = state.runtimeConnected &&
                    !state.messageBusy && state.cleanupPreviewFresh && (preview?.planned ?: 0) > 0) {
                    BeaconText(stringResource(R.string.storage_clean_action))
                }
            }
        }

        SettingsGroup(stringResource(R.string.settings_danger_section_title)) {
            SettingsCommandRow(stringResource(R.string.settings_cleanup_all_title),
                icon = io.github.beacon.app.ui.icons.BeaconIcons.Delete,
                enabled = state.runtimeConnected && !state.messageBusy, danger = true,
                onClick = { clearAllStage = 1 })
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
