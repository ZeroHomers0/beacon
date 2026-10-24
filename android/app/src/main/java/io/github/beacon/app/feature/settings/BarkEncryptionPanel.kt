package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.domain.policy.BarkEncryptionValidation
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
internal fun BarkEncryptionPanel(state: RuntimeState, actions: RuntimeActions, onFeedback: (String) -> Unit) {
    val bound = state.runtimeConnected && state.configuredEndpoint.isNotBlank()
    var configured by remember(state.configuredEndpoint) { mutableStateOf<Boolean?>(null) }
    var failed by remember(state.configuredEndpoint) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var request by remember { mutableIntStateOf(0) }
    var editing by remember(state.configuredEndpoint, state.runtimeConnected) { mutableStateOf(false) }
    var removing by remember(state.configuredEndpoint, state.runtimeConnected) { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val busy = state.messageBusy || state.transportBusy || submitting
    LaunchedEffect(bound, state.configuredEndpoint, attempt) {
        val generation = ++request
        configured = null
        failed = false
        if (bound) actions.barkEncryptionConfigured {
            if (request == generation) { configured = it; failed = it == null }
        }
    }
    SettingsGroup(stringResource(R.string.bark_decryption_title)) {
        SettingsRow(stringResource(R.string.bark_decryption_configure),
            stringResource(when {
                !bound -> R.string.bark_decryption_bind_first
                failed -> R.string.bark_decryption_read_failed
                configured == null -> R.string.bark_decryption_loading
                configured == true -> R.string.bark_decryption_configured
                else -> R.string.bark_decryption_not_configured
            }), BeaconIcons.CredentialUpdate, iconTint = MaterialTheme.colorScheme.onSurface, enabled = bound && !busy && !failed && configured != null,
            onClick = { error = ""; editing = true })
        Column(Modifier.padding(horizontal = BeaconUiMetrics.CardInset, vertical = BeaconUiMetrics.Gap8),
            verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
            SettingsHint(stringResource(R.string.bark_decryption_description))
            if (failed) SettingsTextAction(stringResource(R.string.action_retry), BeaconIcons.Sync,
                enabled = !busy, onClick = { attempt++ })
        }
        if (configured == true) SettingsCommandRow(stringResource(R.string.bark_decryption_remove),
            icon = BeaconIcons.Delete, danger = true, enabled = bound && !busy,
            onClick = { error = ""; removing = true })
    }
    if (editing) BarkEncryptionEditor(busy, error, onDismiss = { if (!submitting) editing = false },
        onSave = { key, iv ->
            submitting = true
            error = ""
            actions.barkEncryption(key, iv, onFailure = { error = it; submitting = false }) {
                submitting = false; editing = false; configured = true; onFeedback(it)
            }
        })
    if (removing) BeaconConfirmDialog(stringResource(R.string.bark_decryption_remove),
        stringResource(R.string.bark_decryption_remove_hint),
        onDismiss = { if (!submitting) removing = false },
        onConfirm = {
            submitting = true; error = ""
            actions.barkEncryption("", "", onFailure = { error = it; submitting = false }) {
                submitting = false; removing = false; configured = false; onFeedback(it)
            }
        }, enabled = bound && !busy, dismissEnabled = !submitting,
        confirmText = stringResource(R.string.bark_decryption_remove_confirm), errorText = error.takeIf { it.isNotEmpty() })
}

@Composable
private fun BarkEncryptionEditor(busy: Boolean, error: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    // Secrets exist only while this dialog is composed: no saved state and no readback.
    var key by remember { mutableStateOf("") }
    var iv by remember { mutableStateOf("") }
    val keyBytes = key.toByteArray(Charsets.UTF_8).size
    val ivBytes = iv.toByteArray(Charsets.UTF_8).size
    BeaconAlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { BeaconText(stringResource(R.string.bark_decryption_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12)) {
                SettingsHint(stringResource(R.string.bark_decryption_edit_hint))
                BeaconTextField(key, { key = it.take(64) }, Modifier.fillMaxWidth(),
                    label = { BeaconText(stringResource(R.string.bark_decryption_key)) },
                    labelText = stringResource(R.string.bark_decryption_key), singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = key.isNotEmpty() && keyBytes !in listOf(16, 24, 32))
                SettingsHint(stringResource(R.string.bark_decryption_key_length, keyBytes))
                BeaconTextField(iv, { iv = it.take(64) }, Modifier.fillMaxWidth(),
                    label = { BeaconText(stringResource(R.string.bark_decryption_iv)) },
                    labelText = stringResource(R.string.bark_decryption_iv), singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = iv.isNotEmpty() && ivBytes != 16)
                SettingsHint(stringResource(R.string.bark_decryption_iv_length, ivBytes))
                if (error.isNotEmpty()) SettingsOverviewField(stringResource(R.string.settings_validation_title), error, error = true)
            }
        }, confirmButton = {
            BeaconButton(onClick = { onSave(key, iv) }, enabled = !busy && BarkEncryptionValidation.valid(key, iv)) {
                BeaconText(stringResource(R.string.action_save))
            }
        }, dismissButton = { BeaconCancelButton(onDismiss, enabled = !busy) }, equalWidthActions = true)
}
