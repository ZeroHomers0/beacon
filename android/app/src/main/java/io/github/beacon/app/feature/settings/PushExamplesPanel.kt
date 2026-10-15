package io.github.beacon.app.feature.settings

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
    var powershell by rememberSaveable { mutableStateOf(false) }
    var json by rememberSaveable { mutableStateOf(false) }
    SettingsGroup("") {
        SettingsRow(
            stringResource(R.string.push_examples_title),
            stringResource(R.string.ui_v2_examples_formats),
            BeaconIcons.Code,
        ) {
            expanded = !expanded
        }
        if (expanded) {
            SettingsFieldDivider()
            Column(
                Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset),
                verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap),
            ) {
                SettingsFieldGroup(
                    {
                        BeaconDropdownPreference(
                            stringResource(R.string.push_example_shell),
                            listOf("curl", "PowerShell"),
                            if (powershell) 1 else 0,
                        ) {
                            powershell = it == 1
                        }
                    },
                    {
                        BeaconDropdownPreference(
                            stringResource(R.string.push_example_format),
                            listOf(stringResource(R.string.push_example_path), "JSON"),
                            if (json) 1 else 0,
                        ) {
                            json = it == 1
                        }
                    },
                )
                BeaconActionButton(
                    stringResource(R.string.push_example_copy),
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
                                    resources.getString(R.string.settings_clipboard_push_example),
                                    example,
                                    sensitive = true,
                                )
                        )
                            onFeedback(resources.getString(R.string.settings_copied_hint))
                    }
                }
            }
        }
    }
}
