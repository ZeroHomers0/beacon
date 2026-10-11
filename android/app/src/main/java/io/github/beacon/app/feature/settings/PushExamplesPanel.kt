package io.github.beacon.app.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
fun PushExamplesPanel(actions: RuntimeActions, enabled: Boolean, endpoint: String) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    UiSection(stringResource(R.string.push_examples_title)) {
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
                copied = false
                actions.copyPushKey { key ->
                    val address = endpoint.trimEnd('/') + "/" + key
                    val example = runCatching {
                        if (json) PushExamples.json(address, powershell)
                        else PushExamples.create(address, powershell)
                    }
                        .getOrNull()
                    if (example != null) {
                        val clip =
                            ClipData.newPlainText(
                                resources.getString(R.string.settings_clipboard_push_example),
                                example,
                            )
                        clip.description.extras =
                            PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                        copied = true
                    }
                }
            }
        }
        // Copying leaves no other trace on screen, so the panel confirms it in place.
        if (copied) SettingsHint(stringResource(R.string.settings_copied_hint))
    }
}
