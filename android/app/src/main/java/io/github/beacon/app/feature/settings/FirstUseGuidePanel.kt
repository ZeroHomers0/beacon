package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*

/** Explicit, resumable onboarding; only an actual push can complete the delivery step. */
@Composable
fun FirstUseGuidePanel(
    state: RuntimeState,
    notificationsAllowed: Boolean,
    actions: RuntimeActions,
    onNavigate: (SettingsScreen) -> Unit,
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val preferences = LocalContext.current.getSharedPreferences("first_use_guide", 0)
    var result by remember { mutableStateOf("") }
    var testSent by remember { mutableStateOf(false) }
    var testConfirmation by remember { mutableStateOf(false) }
    val busy = !state.runtimeConnected || state.transportBusy || state.messageBusy
    UiSection(stringResource(R.string.settings_guide_title)) {
        DiagnosticRow(
            resources.getString(R.string.settings_guide_server),
            if (state.configuredEndpoint.isBlank())
                resources.getString(R.string.settings_guide_unbound)
            else resources.getString(R.string.settings_guide_bound),
        )
        BeaconOutlinedButton(
            onClick = { onNavigate(SettingsScreen.CONNECTION) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BeaconText(stringResource(R.string.settings_guide_configure))
        }
        DiagnosticRow(
            resources.getString(R.string.settings_guide_notifications),
            if (notificationsAllowed)
                resources.getString(R.string.settings_guide_notifications_allowed)
            else resources.getString(R.string.settings_guide_notifications_denied),
        )
        BeaconOutlinedButton(
            onClick = { onNavigate(SettingsScreen.NOTIFICATIONS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BeaconText(stringResource(R.string.settings_guide_check_notifications))
        }
        SettingsHint(stringResource(R.string.settings_guide_receive_hint))
        BeaconOutlinedButton(
            onClick = { onNavigate(SettingsScreen.RECEPTION) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BeaconText(stringResource(R.string.settings_guide_receive_action))
        }
        SettingsHint(stringResource(R.string.settings_guide_test_hint))
        BeaconButton(
            enabled = !busy && state.configuredEndpoint.isNotBlank(),
            onClick = { testConfirmation = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BeaconText(stringResource(R.string.settings_guide_test_action))
        }
        if (result.isNotEmpty())
            OperationResultPanel(stringResource(R.string.settings_guide_test_action), result)
        BeaconOutlinedButton(
            enabled = testSent && notificationsAllowed,
            onClick = {
                preferences.edit().putBoolean("completed", true).apply()
                onNavigate(SettingsScreen.HOME)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BeaconText(stringResource(R.string.settings_guide_done))
        }
    }
    if (testConfirmation)
        BeaconAlertDialog(
            onDismissRequest = { testConfirmation = false },
            title = { BeaconText(stringResource(R.string.settings_setup_test_title)) },
            text = { SettingsHint(stringResource(R.string.settings_setup_test_hint)) },
            confirmButton = {
                BeaconButton(
                    onClick = {
                        testConfirmation = false
                        actions.testDelivery {
                            result = it
                            testSent = true
                        }
                    }
                ) {
                    BeaconText(stringResource(R.string.settings_setup_test_send))
                }
            },
            dismissButton = {
                BeaconCancelButton(onClick = { testConfirmation = false })
            },
        )
}
