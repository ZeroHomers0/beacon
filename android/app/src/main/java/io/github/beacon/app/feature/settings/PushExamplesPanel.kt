package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.policy.PushExamples
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
fun PushExamplesPanel(actions: RuntimeActions, enabled: Boolean, endpoint: String, onFeedback: (String) -> Unit = {}) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current
    SettingsSection(stringResource(R.string.push_examples_title)) {
        SettingsHint(stringResource(R.string.push_examples_hint))
        listOf(false to false, true to false, false to true, true to true).forEach {
            (powershell, json) ->
            // All four buttons copy to the clipboard; one action, one icon.
            BeaconActionButton(
                stringResource(
                    if (json && powershell) R.string.push_examples_json_powershell
                    else if (json) R.string.push_examples_json_curl
                    else if (powershell) R.string.push_examples_powershell
                    else R.string.push_examples_curl
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
                    if (example != null) {
                        val copied = copyToClipboard(
                                context,
                                resources.getString(R.string.settings_clipboard_push_example),
                                example,
                                sensitive = true,
                            )
                        if (copied) onFeedback(resources.getString(R.string.settings_copied_hint))
                    }
                }
            }
        }
    }
}
