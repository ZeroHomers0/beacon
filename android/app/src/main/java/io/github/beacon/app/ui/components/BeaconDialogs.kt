@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R

/**
 * Single-action dialogs pass no [dismissButton]; the confirm button then spans the full width
 * instead of sharing a row with an invisible button. Use one only for an acknowledgement — a dialog
 * that changes state keeps both actions.
 */
@Composable
fun BeaconAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    val actions: @Composable () -> Unit = {
        if (dismissButton == null)
            CompositionLocalProvider(LocalDialogAction provides true) { confirmButton() }
        else BeaconActionPair(dismissButton, confirmButton)
    }
    if (miuix()) {
        val show = remember { mutableStateOf(true) }
        top.yukonga.miuix.kmp.extra.WindowDialog(show, onDismissRequest = onDismissRequest) {
            Column(
                Modifier.heightIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                    title()
                }
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    text()
                }
                actions()
            }
        }
    } else
        androidx.compose.material3.AlertDialog(
            onDismissRequest,
            confirmButton = actions,
            title = title,
            text = text,
        )
}

@Composable
fun BeaconActionPair(dismiss: @Composable () -> Unit, confirm: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CompositionLocalProvider(LocalDialogAction provides true) {
            Box(Modifier.weight(1f), propagateMinConstraints = true) { dismiss() }
            Box(Modifier.weight(1f), propagateMinConstraints = true) { confirm() }
        }
    }
}

/**
 * The confirmation dialog the whole app shares: a title, one line of explanation and a
 * cancel/confirm pair. The confirm label defaults to the title so the common case reads as
 * 「删除设备 / 删除设备」 instead of inventing a second wording.
 *
 * [danger] fills the confirm button with the error container and is reserved for actions that
 * cannot be undone. [enabled] and [dismissEnabled] stay separate because a dialog that keeps the
 * user inside while a call is in flight disables both, while one whose confirm is simply not
 * applicable yet still allows a plain dismissal. Anything richer than a single sentence still
 * builds on [BeaconAlertDialog] directly — a two-stage confirmation must not be flattened into one
 * step.
 */
@Composable
fun BeaconConfirmDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = title,
    danger: Boolean = true,
    enabled: Boolean = true,
    dismissEnabled: Boolean = true,
    dismissText: String = stringResource(R.string.action_cancel),
) {
    BeaconAlertDialog(
        onDismissRequest = onDismiss,
        title = { BeaconText(title) },
        text = { SettingsHint(description) },
        confirmButton = {
            if (danger) BeaconDangerButton(onConfirm, enabled = enabled) { BeaconText(confirmText) }
            else BeaconButton(onConfirm, enabled = enabled) { BeaconText(confirmText) }
        },
        dismissButton = {
            BeaconOutlinedButton(onDismiss, enabled = dismissEnabled) { BeaconText(dismissText) }
        },
    )
}

/** The cancel half of [BeaconConfirmDialog], for dialogs that need a custom body. */
@Composable
fun BeaconCancelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BeaconOutlinedButton(onClick, modifier, enabled) {
        BeaconText(stringResource(R.string.action_cancel))
    }
}

/**
 * Window-level list popup follows KSU: anchored to the action, with a dimmed backdrop, spring
 * entrance, predictive Back and an animated outside-tap dismissal. Keep the host composed while
 * closing so the library can finish its exit animation.
 */
@Composable
fun BeaconDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val show = remember { mutableStateOf(expanded) }
    SideEffect { show.value = expanded }
    top.yukonga.miuix.kmp.extra.WindowListPopup(
        show = show,
        popupPositionProvider =
            top.yukonga.miuix.kmp.basic.ListPopupDefaults.ContextMenuPositionProvider,
        alignment = top.yukonga.miuix.kmp.basic.PopupPositionProvider.Align.TopEnd,
        enableWindowDim = true,
        onDismissRequest = onDismissRequest,
        maxHeight = 360.dp,
    ) {
        // Both styles use the same window interaction; menu typography and items remain themed.
        top.yukonga.miuix.kmp.basic.ListPopupColumn {
            Column(Modifier.padding(8.dp), content = content)
        }
    }
}

@Composable
fun BeaconDropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit) {
    if (miuix())
        BeaconTextButton(onClick, Modifier.fillMaxWidth()) {
            CompositionLocalProvider(
                top.yukonga.miuix.kmp.theme.LocalContentColor provides
                    MaterialTheme.colorScheme.onSurface
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { text() }
            }
        }
    else androidx.compose.material3.DropdownMenuItem(text, onClick)
}
