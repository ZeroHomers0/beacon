package io.github.beacon.app.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState

/** Keep one business feedback controller; each style owns rendering, timing and dismissal. */
@Composable
internal fun BeaconFeedbackHost(source: SnackbarHostState, modifier: Modifier = Modifier) {
    if (miuix()) {
        val current = source.currentSnackbarData
        // The native host supports stacked messages. Beacon deliberately replaces the old one.
        val host = remember(current) { top.yukonga.miuix.kmp.basic.SnackbarHostState() }
        LaunchedEffect(current) {
            if (current != null) {
                val duration = when (current.visuals.duration) {
                    SnackbarDuration.Short -> top.yukonga.miuix.kmp.basic.SnackbarDuration.Short
                    SnackbarDuration.Long -> top.yukonga.miuix.kmp.basic.SnackbarDuration.Long
                    SnackbarDuration.Indefinite -> top.yukonga.miuix.kmp.basic.SnackbarDuration.Indefinite
                }
                val result = host.showSnackbar(current.visuals.message, current.visuals.actionLabel,
                    current.visuals.withDismissAction, duration)
                if (result == top.yukonga.miuix.kmp.basic.SnackbarResult.ActionPerformed) current.performAction()
                else current.dismiss()
            }
        }
        key(current) { top.yukonga.miuix.kmp.basic.SnackbarHost(host, modifier) }
    } else androidx.compose.material3.SnackbarHost(source, modifier)
}
