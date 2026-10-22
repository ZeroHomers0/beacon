package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.policy.PushExamples
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
fun PushExamplesPanel(
    actions: RuntimeActions,
    enabled: Boolean,
    endpoint: String,
    onFeedback: (String) -> Unit = {},
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var powershell by rememberSaveable { mutableStateOf(false) }
    var json by rememberSaveable { mutableStateOf(false) }
    var copied by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(copied, powershell, json) {
        if (copied) { kotlinx.coroutines.delay(1500); copied = false }
    }
    SettingsGroup(stringResource(R.string.settings_api_heading)) {
        SettingsRow(stringResource(R.string.push_examples_title), stringResource(R.string.ui_v2_examples_formats),
            BeaconIcons.Code, iconTint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            selection = true, onClick = { expanded = true })
    }
    if (expanded) BeaconAlertDialog(
        onDismissRequest = { expanded = false },
        title = { BeaconText(stringResource(R.string.push_examples_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap)) {
                BeaconDropdownPreference(stringResource(R.string.settings_example_shell),
                    listOf("cURL", "PowerShell"), if (powershell) 1 else 0) { powershell = it == 1; copied = false }
                BeaconDropdownPreference(stringResource(R.string.settings_example_format),
                    listOf("URL", "JSON"), if (json) 1 else 0) { json = it == 1; copied = false }
                val preview = runCatching {
                    val address = endpoint.trimEnd('/') + "/YOUR_PUSH_KEY"
                    if (json) PushExamples.json(address, powershell) else PushExamples.create(address, powershell)
                }.getOrDefault("")
                androidx.compose.foundation.text.selection.SelectionContainer {
                    BeaconText(preview, style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }
            }
        },
        confirmButton = {
            SettingsToolAction(stringResource(R.string.settings_example_copy), BeaconIcons.Copy,
                enabled = enabled, completed = copied, onClick = {
                actions.copyPushKey { key ->
                    val address = endpoint.trimEnd('/') + "/" + key
                    val example = runCatching {
                        if (json) PushExamples.json(address, powershell) else PushExamples.create(address, powershell)
                    }.getOrNull()
                    if (example != null) {
                        copied = copyToClipboard(context, resources.getString(R.string.settings_clipboard_push_example),
                            example, sensitive = true)
                        if (copied) onFeedback(resources.getString(R.string.settings_copied_hint))
                    }
                }
            })
        },
        dismissButton = { BeaconTextButton(onClick = { expanded = false }) { BeaconText(stringResource(R.string.action_close)) } },
    )
}
