package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    SettingsGroup(stringResource(R.string.settings_api_heading)) {
        SettingsCommandRow(
            title = stringResource(R.string.binding_copy_push_address),
            icon = BeaconIcons.Copy,
            enabled = enabled,
            onClick = {
                actions.copyPushKey { key ->
                    if (copyToClipboard(context, resources.getString(R.string.settings_clipboard_push_key),
                            key, sensitive = true)) {
                        onFeedback(resources.getString(R.string.settings_copied_hint))
                    }
                }
            },
        )
        SettingsRow(
            stringResource(R.string.push_examples_title),
            stringResource(R.string.ui_v2_examples_formats),
            BeaconIcons.Code,
        ) {
            expanded = true
        }
    }
    if (expanded)
        BeaconAlertDialog(
            onDismissRequest = { expanded = false },
            title = { BeaconText(stringResource(R.string.push_examples_title)) },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
                ) {
                    listOf(false to false, true to false, false to true, true to true).forEach {
                        (powershell, json) ->
                        BeaconActionButton(
                            stringResource(
                                if (json && powershell) R.string.push_example_json_powershell
                                else if (json) R.string.push_example_json_curl
                                else if (powershell) R.string.push_example_powershell
                                else R.string.push_example_curl
                            ),
                            BeaconIcons.Copy,
                            Modifier.fillMaxWidth(),
                            enabled,
                        ) {
                            actions.copyPushKey { key ->
                                val address = endpoint.trimEnd('/') + "/" + key
                                val example = runCatching {
                                    if (json) PushExamples.json(address, powershell)
                                    else PushExamples.create(address, powershell)
                                }
                                    .getOrNull()
                                if (
                                    example != null &&
                                        copyToClipboard(
                                            context,
                                            resources.getString(
                                                R.string.settings_clipboard_push_example
                                            ),
                                            example,
                                            sensitive = true,
                                        )
                                )
                                    onFeedback(resources.getString(R.string.settings_copied_hint))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                BeaconButton(onClick = { expanded = false }) {
                    BeaconText(stringResource(R.string.action_close))
                }
            },
        )
}
