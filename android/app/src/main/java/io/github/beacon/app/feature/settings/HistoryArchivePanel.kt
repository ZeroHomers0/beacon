package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*

@Composable
fun HistoryArchivePanel(actions: RuntimeActions, busy: Boolean, grouped: Boolean = true) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    var operation by remember { mutableStateOf<Boolean?>(null) }
    var encrypted by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf<Boolean?>(null) }
    var hint by remember { mutableStateOf("") }
    var filePassword by remember { mutableStateOf("") }
    var fileEncrypted by remember { mutableStateOf(true) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingImport by remember { mutableStateOf(false) }
    LaunchedEffect(pendingUri, busy) {
        val uri = pendingUri
        if (uri != null && !busy) {
            val secret = filePassword
            filePassword = ""
            pendingUri = null
            if (fileEncrypted && secret.length < 8) {
                failed = true
                hint = resources.getString(R.string.settings_archive_password_expired)
            } else
                actions.historyArchive(
                    uri.toString(),
                    secret,
                    pendingImport,
                    fileEncrypted,
                    onFailure = {
                        failed = true
                        hint = it
                    },
                ) {
                    failed = false
                    hint = it
                }
        }
    }
    fun run(uri: android.net.Uri?, importing: Boolean) {
        if (uri == null) filePassword = ""
        else {
            pendingImport = importing
            pendingUri = uri
        }
    }
    val create =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) {
            run(it, false)
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { run(it, true) }
    val content: @Composable () -> Unit = {
        SettingsActionRow(
            stringResource(R.string.settings_archive_export),
            stringResource(R.string.ui_v2_export_history_hint),
            io.github.beacon.app.ui.icons.BeaconIcons.Backup,
            enabled = !busy,
            onClick = {
                operation = false
                password = ""
                confirmPassword = ""
                encrypted = true
            },
        )
        SettingsActionRow(
            stringResource(R.string.settings_archive_import),
            stringResource(R.string.ui_v2_import_history_hint),
            io.github.beacon.app.ui.icons.BeaconIcons.History,
            enabled = !busy,
            onClick = {
                operation = true
                password = ""
                confirmPassword = ""
                encrypted = true
            },
        )
        if (hint.isNotEmpty())
            OperationResultPanel(stringResource(R.string.settings_archive_title), hint, failed)
    }
    if (grouped) SettingsGroup(stringResource(R.string.settings_archive_title)) { content() }
    else content()
    if (operation != null)
        BeaconAlertDialog(
            onDismissRequest = {
                operation = null
                password = ""
                confirmPassword = ""
            },
            title = {
                BeaconText(
                    if (operation == true)
                        resources.getString(R.string.settings_archive_import_title)
                    else resources.getString(R.string.settings_archive_export_title)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12)) {
                    BeaconPreferenceToggle(
                        resources.getString(R.string.settings_archive_encryption),
                        resources.getString(R.string.settings_archive_encryption_hint),
                        encrypted,
                        true,
                    ) {
                        encrypted = it
                    }
                    if (encrypted) {
                        BeaconTextField(
                            password,
                            { password = it.take(256) },
                            Modifier.fillMaxWidth(),
                            label = {
                                BeaconText(stringResource(R.string.settings_archive_password))
                            },
                            labelText = resources.getString(R.string.settings_archive_password),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        if (operation == false)
                            BeaconTextField(
                                confirmPassword,
                                { confirmPassword = it.take(256) },
                                Modifier.fillMaxWidth(),
                                label = {
                                    BeaconText(
                                        resources.getString(
                                            R.string.settings_archive_password_repeat
                                        )
                                    )
                                },
                                labelText =
                                    resources.getString(R.string.settings_archive_password_repeat),
                                visualTransformation = PasswordVisualTransformation(),
                            )
                        SettingsHint(stringResource(R.string.settings_archive_password_hint))
                    }
                }
            },
            confirmButton = {
                BeaconButton(
                    enabled =
                        !encrypted ||
                            (password.length >= 8 &&
                                (operation == true || password == confirmPassword)),
                    onClick = {
                        val importing = operation == true
                        filePassword = if (encrypted) password else ""
                        fileEncrypted = encrypted
                        password = ""
                        confirmPassword = ""
                        operation = null
                        if (importing)
                            open.launch(
                                arrayOf(
                                    "application/json",
                                    "application/octet-stream",
                                    "text/plain",
                                )
                            )
                        else
                            create.launch(
                                if (encrypted) "beacon-messages.beacon" else "beacon-messages.json"
                            )
                    },
                ) {
                    BeaconText(
                        stringResource(
                            if (operation == true) R.string.settings_archive_choose_import
                            else R.string.settings_archive_choose_export
                        )
                    )
                }
            },
            dismissButton = {
                BeaconCancelButton(
                    onClick = {
                        operation = null
                        password = ""
                        confirmPassword = ""
                    }
                )
            },
        )
}
