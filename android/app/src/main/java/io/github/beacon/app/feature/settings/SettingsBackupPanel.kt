package io.github.beacon.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.data.local.SettingsBackup
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsBackupPanel(actions: RuntimeActions, busy: Boolean) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var fileBusy by remember { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val exportFile =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            val payload = exporting
            exporting = null
            if (uri != null && payload != null)
                scope.launch {
                    fileBusy = true
                    try {
                        hint =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                        requireNotNull(
                                                context.contentResolver.openOutputStream(uri, "wt")
                                            )
                                            .use {
                                                it.write(payload.toByteArray(Charsets.UTF_8))
                                            }
                                    }
                                    .fold(
                                        { resources.getString(R.string.settings_backup_export_done) },
                                        { resources.getString(R.string.settings_backup_export_failed) },
                                    )
                            }
                    } finally { fileBusy = false }
                }
        }
    val importFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    fileBusy = true
                    try {
                        val result =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val bytes =
                                        requireNotNull(context.contentResolver.openInputStream(uri))
                                            .use {
                                                io.github.beacon.app.data.local.ArchiveCrypto
                                                    .readBounded(it, SettingsBackup.MAX_BYTES)
                                            }
                                    require(bytes.size <= SettingsBackup.MAX_BYTES)
                                    bytes.toString(Charsets.UTF_8).also { SettingsBackup.validate(it) }
                                }
                            }
                        result
                            .onSuccess {
                                text = it
                                hint = ""
                                importing = true
                            }
                            .onFailure {
                                hint = resources.getString(R.string.settings_backup_invalid_file)
                            }
                    } finally { fileBusy = false }
                }
        }
    SettingsSection(stringResource(R.string.settings_backup_settings_title)) {
        SettingsHint(stringResource(R.string.settings_backup_settings_hint))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BeaconOutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !busy && !fileBusy,
                onClick = {
                    actions.settingsBackup(
                        onFailure = {
                            hint =
                                resources.getString(
                                    R.string.settings_backup_export_failed_connection
                                )
                        }
                    ) { payload ->
                        exporting = payload
                        exportFile.launch("beacon-settings.json")
                    }
                },
            ) {
                BeaconText(stringResource(R.string.settings_backup_export))
            }
            BeaconOutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !busy && !fileBusy,
                onClick = {
                    importFile.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                },
            ) {
                BeaconText(stringResource(R.string.settings_backup_import))
            }
        }
        if (!importing && hint.isNotEmpty())
            OperationResultPanel(stringResource(R.string.settings_backup_title), hint)
    }
    if (importing)
        BeaconAlertDialog(
            onDismissRequest = { if (!busy) importing = false },
            title = { BeaconText(stringResource(R.string.settings_backup_import_title)) },
            text = {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsHint(stringResource(R.string.settings_backup_import_hint))
                    BeaconText(stringResource(R.string.settings_backup_validated))
                    if (hint.isNotEmpty()) BeaconText(hint)
                }
            },
            confirmButton = {
                BeaconButton(
                    enabled = !busy && !fileBusy,
                    onClick = {
                        if (runCatching { SettingsBackup.validate(text) }.isFailure)
                            hint = resources.getString(R.string.settings_backup_invalid_values)
                        else
                            actions.settingsBackup(
                                text,
                                onFailure = {
                                    hint =
                                        resources.getString(
                                            R.string.settings_backup_import_unconfirmed
                                        )
                                },
                            ) {
                                importing = false
                                text = ""
                                hint = resources.getString(R.string.settings_backup_import_done)
                            }
                    },
                ) {
                    BeaconText(stringResource(R.string.settings_backup_import_apply))
                }
            },
            dismissButton = {
                BeaconCancelButton(
                    enabled = !busy && !fileBusy,
                    onClick = {
                        importing = false
                        text = ""
                    },
                )
            },
        )
}
