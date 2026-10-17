package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.*

@Composable
internal fun rememberFirstUseGuideCompleted(): State<Boolean> {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("first_use_guide", 0) }
    val completed =
        remember(preferences) { mutableStateOf(preferences.getBoolean("completed", false)) }
    DisposableEffect(preferences) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == "completed") completed.value = prefs.getBoolean("completed", false)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        completed.value = preferences.getBoolean("completed", false)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return completed
}

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
    val endpoint = state.configuredEndpoint
    val sameBinding = preferences.getString("progress_endpoint", "") == endpoint
    var result by remember(endpoint) { mutableStateOf("") }
    var testSent by
        remember(endpoint) {
            mutableStateOf(sameBinding && preferences.getBoolean("test_sent", false))
        }
    var received by
        remember(endpoint) {
            mutableStateOf(sameBinding && preferences.getBoolean("test_received", false))
        }
    var testFailed by remember { mutableStateOf<Boolean?>(null) }
    var testConfirmation by remember { mutableStateOf(false) }
    val currentEndpoint by rememberUpdatedState(state.configuredEndpoint)
    LaunchedEffect(endpoint, testSent, received) {
        if (state.connectionSummaryLoaded)
            preferences
                .edit()
                .putString("progress_endpoint", endpoint)
                .putBoolean("test_sent", testSent)
                .putBoolean("test_received", received)
                .apply()
    }
    val busy = !state.runtimeConnected || state.transportBusy || state.messageBusy
    SettingsSection(stringResource(R.string.settings_guide_title)) {
        GuideStep(
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
        GuideStep(
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
        GuideStep(
            stringResource(R.string.settings_guide_receive_title),
            stringResource(R.string.settings_guide_receive_hint),
        )
        BeaconOutlinedButton(
            onClick = { onNavigate(SettingsScreen.RECEPTION) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BeaconText(stringResource(R.string.settings_guide_receive_action))
        }
        GuideStep(
            stringResource(R.string.settings_guide_test_title),
            stringResource(R.string.settings_guide_test_hint),
        )
        BeaconButton(
            enabled = !busy && state.configuredEndpoint.isNotBlank(),
            onClick = { testConfirmation = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BeaconText(stringResource(R.string.settings_guide_test_action))
        }
        if (result.isNotEmpty())
            OperationResultPanel(
                stringResource(R.string.settings_guide_test_action),
                result,
                testFailed,
            )
        if (testSent)
            Row(
                Modifier.fillMaxWidth().heightIn(min = BeaconUiMetrics.FieldMinHeight),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                BeaconCheckbox(
                    received,
                    { received = it },
                    enabled = !busy,
                    contentDescription = stringResource(R.string.settings_guide_received),
                )
                BeaconText(
                    stringResource(R.string.settings_guide_received),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        SettingsHint(
            stringResource(
                when {
                    state.configuredEndpoint.isBlank() -> R.string.settings_guide_next_bind
                    !notificationsAllowed -> R.string.settings_guide_next_permission
                    !testSent -> R.string.settings_guide_next_test
                    !received -> R.string.settings_guide_next_confirm
                    else -> R.string.settings_guide_ready
                }
            )
        )
        BeaconOutlinedButton(
            enabled =
                testSent &&
                    received &&
                    notificationsAllowed &&
                    !busy &&
                    state.configuredEndpoint.isNotBlank(),
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
                        val requestedEndpoint = currentEndpoint
                        testSent = false
                        received = false
                        testFailed = null
                        actions.testDelivery(
                            onFailure = {
                                if (currentEndpoint == requestedEndpoint) {
                                    result = it
                                    testFailed = true
                                }
                            }
                        ) {
                            if (currentEndpoint == requestedEndpoint) {
                                result = it
                                testSent = true
                                testFailed = false
                            }
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

@Composable
private fun GuideStep(title: String, description: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4)) {
        BeaconText(title, style = MaterialTheme.typography.titleMedium)
        BeaconText(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun resetFirstUseGuideProgress(context: android.content.Context) {
    context.getSharedPreferences("first_use_guide", 0).edit().clear().apply()
}

internal fun syncFirstUseGuideBinding(context: android.content.Context, endpoint: String) {
    val preferences = context.getSharedPreferences("first_use_guide", 0)
    if (preferences.getString("progress_endpoint", null) != endpoint)
        preferences.edit().clear().putString("progress_endpoint", endpoint).apply()
}
