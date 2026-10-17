package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.RuntimeEvent
import io.github.beacon.app.feature.diagnostics.AndroidRuntimeEventText
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.BeaconOutlinedButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.SettingsGroup
import io.github.beacon.app.ui.components.SettingsPreferenceActions

/**
 * Export of the bounded reception journal.
 *
 * The journal already existed (`RuntimeEventStore`) but had no way out of the device, so a user
 * who hit a reception problem had nothing to attach to a report. The export is the journal's own
 * allowlisted text plus the build fingerprint: no address, credential, message body or message id
 * can reach it, because [AndroidRuntimeEventText] only ever renders fixed enum labels and a
 * timestamp, and the surrounding lines are compile-time constants and `BuildConfig`.
 */
internal fun LazyListScope.diagnosticsItems(
    context: Context,
    actions: RuntimeActions,
    onOperationDetails: (String) -> Unit,
) {
    item { DiagnosticsExportPanel(context, actions, onOperationDetails) }
}

@Composable
private fun DiagnosticsExportPanel(
    context: Context,
    actions: RuntimeActions,
    onOperationDetails: (String) -> Unit,
) {
    var reading by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<RuntimeEvent>>(emptyList()) }
    var exported by remember { mutableStateOf(false) }
    val text = remember(context) { AndroidRuntimeEventText(context) }

    SettingsGroup(stringResource(R.string.settings_diagnostics_group)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
        ) {
            BeaconText(
                stringResource(R.string.settings_diagnostics_row),
                style = MaterialTheme.typography.bodyLarge,
            )
            BeaconText(
                stringResource(R.string.settings_diagnostics_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val stateText =
                when {
                    reading -> stringResource(R.string.settings_diagnostics_reading)
                    exported -> stringResource(R.string.settings_diagnostics_shared)
                    unavailable -> stringResource(R.string.settings_diagnostics_unavailable)
                    events.isEmpty() -> stringResource(R.string.settings_diagnostics_empty)
                    else -> stringResource(R.string.settings_diagnostics_count, events.size)
                }
            BeaconText(
                stateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsPreferenceActions {
                BeaconOutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !reading,
                    onClick = {
                        reading = true
                        exported = false
                        unavailable = false
                        actions.eventLog(
                            onFailure = {
                                reading = false
                                unavailable = true
                            },
                        ) { loaded ->
                            reading = false
                            events = loaded
                            if (loaded.isEmpty())
                                onOperationDetails(
                                    context.getString(R.string.settings_diagnostics_empty)
                                )
                            else share(context, text.export(loaded), onOperationDetails)
                            exported = loaded.isNotEmpty()
                        }
                    },
                ) {
                    BeaconText(stringResource(R.string.settings_diagnostics_export))
                }
            }
        }
    }
}

private fun share(context: Context, body: String, onOperationDetails: (String) -> Unit) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.event_export_title))
            putExtra(Intent.EXTRA_TEXT, body)
        }
    try {
        // A chooser keeps the export user-mediated: nothing leaves the device without a tap.
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: android.content.ActivityNotFoundException) {
        onOperationDetails(context.getString(R.string.settings_share_unavailable))
    } catch (_: SecurityException) {
        onOperationDetails(context.getString(R.string.settings_share_blocked))
    }
}
