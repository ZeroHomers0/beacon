package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.CleanupPreview
import io.github.beacon.app.domain.model.CleanupSettings
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.domain.model.StorageHealth
import io.github.beacon.app.domain.model.StoragePressure
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
    var saveConfirmed by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.runtimeConnected, attempt) {
        settingsLoaded = false
        loadFailed = false
        saving = false
        saveConfirmed = false
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
    fun save(value: CleanupSettings) {
        saving = true
        loadFailed = false
        saveConfirmed = false
        saveFailed = false
        actions.cleanupSettings(value, onFailure = {
            loadFailed = true
            saving = false
            saveFailed = true
        }) {
            settings = it
            saving = false
            saveConfirmed = true
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
    Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap)) {
        BeaconSaveFeedback(saving = saving, saved = saveConfirmed)
        // Without this the page rendered every control disabled with no explanation; a failed read
        // is now visible and recoverable instead of looking like a broken toggle.
        if (loadFailed) {
            UiSection(stringResource(if (saveFailed) R.string.storage_save_failed_title else R.string.storage_load_failed_title)) {
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
        UiSection(stringResource(R.string.storage_usage_title)) {
            StorageUsageChart(preview)
            SettingsMetricGrid(
                listOf(
                    Pair(
                        resources.getString(R.string.settings_storage_database),
                        preview?.databaseBytes?.let {
                            android.text.format.Formatter.formatFileSize(context, it)
                        } ?: "—",
                    ),
                    Pair(
                        resources.getString(R.string.ui_nav_messages_title),
                        preview?.total?.let {
                            resources.getString(R.string.settings_cleanup_count, it)
                        } ?: resources.getString(R.string.storage_space_unknown),
                    ),
                    Pair(
                        resources.getString(R.string.msg_unread_label),
                        preview?.unread?.let {
                            resources.getString(R.string.settings_cleanup_count, it)
                        } ?: "—",
                    ),
                    Pair(
                        resources.getString(R.string.settings_storage_receipts),
                        preview?.pending?.let {
                            resources.getString(R.string.settings_cleanup_count, it)
                        } ?: "—",
                    ),
                    Pair(
                        stringResource(R.string.storage_waiting_reminders),
                        preview?.pendingNotifications?.let {
                            resources.getString(R.string.settings_cleanup_count, it)
                        } ?: "—",
                    ),
                    Pair(
                        stringResource(R.string.storage_deleted_records),
                        preview?.tombstones?.let {
                            resources.getString(R.string.settings_cleanup_count, it)
                        } ?: "—",
                    ),
                    Pair(
                        stringResource(R.string.storage_wal),
                        preview?.walBytes?.let {
                            android.text.format.Formatter.formatFileSize(context, it)
                        } ?: "—",
                    ),
                )
            )
            DiagnosticRow(
                stringResource(R.string.storage_sampled),
                preview?.sampledAtMs?.let { formatMessageTime(it) } ?: "—",
            )
            SettingsHint(stringResource(R.string.storage_breakdown_note))
            SettingsHint(stringResource(R.string.storage_size_note))
        }
        UiSection(stringResource(R.string.settings_cleanup_auto_title)) {
            BeaconPreferenceToggle(
                resources.getString(R.string.settings_cleanup_auto_toggle),
                resources.getString(R.string.settings_cleanup_auto_hint),
                settings.automatic,
                enabled = settingsLoaded && !state.messageBusy && !saving,
            ) {
                save(settings.copy(automatic = it))
            }
            DiagnosticRow(
                stringResource(R.string.storage_last_result_label),
                settings.lastResult,
            )
        }
        UiSection(stringResource(R.string.storage_rules_title)) {
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
            BeaconDropdownPreference(
                stringResource(R.string.storage_max_messages),
                limits.map { resources.getString(R.string.settings_cleanup_count, it) },
                limits.indexOf(policy.maxMessages),
                enabled = !state.messageBusy && settingsLoaded && !saving,
            ) {
                save(settings.copy(policy = policy.copy(maxMessages = limits[it])))
            }
            BeaconActionButton(
                stringResource(R.string.storage_custom),
                io.github.beacon.app.ui.icons.BeaconIcons.Tune,
                Modifier.fillMaxWidth(),
                !state.messageBusy && settingsLoaded && !saving,
            ) {
                custom = true
            }
            BeaconPreferenceToggle(
                stringResource(R.string.storage_keep_unread),
                stringResource(R.string.storage_keep_unread_desc),
                policy.keepUnread,
                enabled = !state.messageBusy && settingsLoaded && !saving,
            ) {
                save(settings.copy(policy = policy.copy(keepUnread = it)))
            }
            SettingsHint(stringResource(R.string.storage_manual_note))
        }
        StorageHealthPanel(preview)
        UiSection(stringResource(R.string.storage_preview_title)) {
            if (preview != null) {
                SettingsMetricGrid(
                    listOf(
                        stringResource(R.string.settings_cleanup_eligible) to
                            resources.getString(R.string.settings_cleanup_count, preview.eligible),
                        stringResource(R.string.settings_cleanup_protected) to
                            resources.getString(R.string.settings_cleanup_count, preview.protected),
                        stringResource(R.string.settings_cleanup_planned) to
                            resources.getString(R.string.settings_cleanup_count, preview.planned),
                    )
                )
                BeaconText(
                    stringResource(
                        when (
                            io.github.beacon.app.domain.model.StorageHealth.explanation(preview)
                        ) {
                            io.github.beacon.app.domain.model.CleanupExplanation.EMPTY ->
                                R.string.storage_reason_empty
                            io.github.beacon.app.domain.model.CleanupExplanation.WITHIN_RULES ->
                                R.string.storage_reason_within
                            io.github.beacon.app.domain.model.CleanupExplanation.PROTECTED ->
                                R.string.storage_reason_protected
                            io.github.beacon.app.domain.model.CleanupExplanation.READY ->
                                R.string.storage_reason_ready
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            BeaconText(
                stringResource(R.string.storage_batch_note),
                style = MaterialTheme.typography.bodySmall,
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
                        enabled = !state.messageBusy && (preview?.planned ?: 0) > 0,
                    ) {
                        BeaconText(stringResource(R.string.storage_clean_action))
                    }
                },
            )
        }
        UiSection(stringResource(R.string.settings_cleanup_all_title)) {
            SettingsHint(resources.getString(R.string.settings_cleanup_all_hint))
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
        CustomCleanupDialog(policy, state.messageBusy, { custom = false }) { value ->
            actions.cleanupSettings(settings.copy(policy = value)) {
                settings = it
                custom = false
            }
        }
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

@Composable
private fun StorageUsageChart(preview: CleanupPreview?) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val total = preview?.total ?: 0L
    val unread = preview?.unread ?: 0L
    val read = (total - unread).coerceAtLeast(0L)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiaryContainer
    val fraction = if (total > 0) unread.toFloat() / total else 0f
    val animated by
        androidx.compose.animation.core.animateFloatAsState(fraction, label = "message counts")
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(128.dp).semantics {
                contentDescription =
                    resources.getString(
                        R.string.settings_storage_chart_description,
                        total,
                        unread,
                        read,
                    )
            },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val stroke =
                    androidx.compose.ui.graphics.drawscope.Stroke(
                        18.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Butt,
                    )
                drawArc(secondary, -90f, 360f, false, style = stroke)
                if (animated > 0) drawArc(primary, -90f, animated * 360f, false, style = stroke)
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                BeaconText(
                    if (preview == null) "—" else total.toString(),
                    style = MaterialTheme.typography.titleLarge,
                )
                BeaconText(
                    resources.getString(R.string.settings_storage_local_messages),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                BeaconText(stringResource(R.string.msg_unread_label), color = primary)
                BeaconText(if (preview == null) "—" else unread.toString())
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                BeaconText(stringResource(R.string.msg_read_label))
                BeaconText(if (preview == null) "—" else read.toString())
            }
        }
        BeaconText(
            resources.getString(R.string.settings_storage_chart_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun StorageHealthPanel(preview: CleanupPreview?) {
    val available = preview?.availableBytes ?: -1L
    val low = StorageHealth.pressure(available) == StoragePressure.LOW
    UiSection(stringResource(R.string.storage_device_space)) {
        DiagnosticRow(
            stringResource(R.string.storage_available),
            if (available < 0) stringResource(R.string.storage_space_unknown)
            else android.text.format.Formatter.formatFileSize(LocalContext.current, available),
        )
        BeaconText(
            stringResource(if (low) R.string.storage_space_low else R.string.storage_space_note),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (low) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
