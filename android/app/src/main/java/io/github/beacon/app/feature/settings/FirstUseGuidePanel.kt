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
    var receiveReviewed by remember(endpoint) { mutableStateOf(sameBinding && preferences.getBoolean("receive_reviewed", false)) }
    val connected = endpoint.isNotBlank()
    val done = listOf(connected, notificationsAllowed, receiveReviewed, testSent && received)
    val currentStep = done.indexOfFirst { !it }.let { if (it < 0) 4 else it }
    SettingsGroup(stringResource(R.string.settings_guide_steps)) {
        Column(Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset)) {
            SettingsHint(stringResource(R.string.settings_guide_progress, done.count { it }, 4))
        }
        val titles = listOf(R.string.settings_guide_server, R.string.settings_guide_notifications,
            R.string.settings_guide_receive_title, R.string.settings_guide_test_title)
        titles.forEachIndexed { index, title ->
            SettingsRow(stringResource(if (index == 3) R.string.settings_guide_test_action else title),
                if (done[index]) stringResource(R.string.settings_complete)
                else if (index == 2) stringResource(receiveModeTitle(state.receivePlan.mode))
                else stringResource(when (index) {
                    0 -> R.string.settings_guide_unbound
                    1 -> R.string.settings_guide_notifications_denied
                    else -> R.string.settings_guide_test_pending
                }),
                if (done[index]) io.github.beacon.app.ui.icons.BeaconIcons.Check else when (index) {
                    0 -> io.github.beacon.app.ui.icons.BeaconIcons.Server
                    1 -> io.github.beacon.app.ui.icons.BeaconIcons.Messages
                    2 -> io.github.beacon.app.ui.icons.BeaconIcons.ReceiveRules
                    else -> io.github.beacon.app.ui.icons.BeaconIcons.Diagnostics
                }, iconTint = if (done[index]) io.github.beacon.app.ui.theme.beaconSuccessColor()
                    else MaterialTheme.colorScheme.onSurface,
                command = index == 3, onClick = {
                    when (index) {
                        0 -> onNavigate(SettingsScreen.CONNECTION)
                        1 -> onNavigate(SettingsScreen.NOTIFICATIONS)
                        2 -> {
                            receiveReviewed = true
                            preferences.edit().putBoolean("receive_reviewed", true).apply()
                            onNavigate(SettingsScreen.RECEPTION)
                        }
                        else -> if (!busy && connected) testConfirmation = true
                    }
                })
            if (index == currentStep || (index == 3 && testSent && !received)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = BeaconUiMetrics.CardInset),
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
                    if (index == 3) {
                        if (result.isNotEmpty()) OperationResultPanel(
                            stringResource(R.string.settings_guide_test_action), result, testFailed)
                        if (testSent) Row(Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            BeaconCheckbox(received, { received = it }, enabled = !busy,
                                contentDescription = stringResource(R.string.settings_guide_received))
                            BeaconText(stringResource(R.string.settings_guide_received), Modifier.weight(1f))
                        }
                    }
                    SettingsHint(stringResource(when (index) {
                        0 -> R.string.settings_guide_next_bind
                        1 -> R.string.settings_guide_next_permission
                        2 -> R.string.settings_guide_receive_hint
                        else -> if (testSent) R.string.settings_guide_next_confirm else R.string.settings_guide_next_test
                    }))
                    Spacer(Modifier.height(BeaconUiMetrics.Gap8))
                }
            }
        }
        if (done.all { it }) Column(Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset)) {
            SettingsActions {
                BeaconButton(enabled = !busy, onClick = {
                    preferences.edit().putBoolean("completed", true).apply()
                    onNavigate(SettingsScreen.HOME)
                }) { BeaconText(stringResource(R.string.settings_guide_done)) }
            }
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

internal fun resetFirstUseGuideProgress(context: android.content.Context) {
    context.getSharedPreferences("first_use_guide", 0).edit().clear().apply()
}

internal fun syncFirstUseGuideBinding(context: android.content.Context, endpoint: String) {
    val preferences = context.getSharedPreferences("first_use_guide", 0)
    if (preferences.getString("progress_endpoint", null) != endpoint)
        preferences.edit().clear().putString("progress_endpoint", endpoint).apply()
}
