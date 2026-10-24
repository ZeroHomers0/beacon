@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.ui.icons.BeaconIcons

@Composable
fun BeaconCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = "",
) {
    if (miuix())
        top.yukonga.miuix.kmp.basic.Checkbox(
            checked,
            onCheckedChange,
            modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).named(contentDescription),
            enabled = enabled,
        )
    else
        androidx.compose.material3.Checkbox(
            checked,
            onCheckedChange,
            modifier.named(contentDescription),
            enabled = enabled,
        )
}

/** Replaces the overflow slot during multi-selection, without changing card geometry. */
@Composable
internal fun BeaconSelectionAction(checked: Boolean, onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, description: String) {
    Box(modifier.then(Modifier.named(description))
        .then(Modifier.toggleable(checked, enabled = enabled,
            role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange)),
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        Box(Modifier.size(BeaconUiMetrics.MessageCardActionIconSize)
            .border(2.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                androidx.compose.foundation.shape.CircleShape)
            .background(if (checked) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                androidx.compose.foundation.shape.CircleShape),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (checked) androidx.compose.material3.Icon(BeaconIcons.Check, null,
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
fun BeaconTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
    labelText: String = "",
    singleLine: Boolean = false,
    enabled: Boolean = true,
    placeholder: String = "",
    isError: Boolean = false,
    compact: Boolean = singleLine,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeHeight = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(focused, imeHeight) {
        if (focused && imeHeight > 0) {
            kotlinx.coroutines.delay(150)
            requester.bringIntoView()
        }
    }
    val fieldModifier =
        Modifier.bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
    if (compact) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6)) {
            label()
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().named(labelText).then(fieldModifier),
                singleLine = singleLine, enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = androidx.compose.foundation.BorderStroke(1.dp,
                            if (isError) MaterialTheme.colorScheme.error
                            else if (focused) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline),
                    ) {
                        Box(Modifier.heightIn(min = BeaconUiMetrics.ControlMinHeight)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
                            if (value.isEmpty() && placeholder.isNotEmpty())
                                BeaconText(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    }
                },
            )
        }
    } else if (miuix())
        Column(modifier, verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6)) {
            label()
            // MIUIX draws the label above the field, so the field needs its own accessible name;
            // Material 3 puts the label inside the field and merges it into the same node.
            top.yukonga.miuix.kmp.basic.TextField(
                value,
                onValueChange,
                Modifier.fillMaxWidth()
                    .heightIn(min = TEXT_FIELD_MIN_HEIGHT)
                    .named(labelText)
                    .then(fieldModifier),
                singleLine = singleLine,
                enabled = enabled,
                label = placeholder,
                useLabelAsPlaceholder = placeholder.isNotEmpty(),
                borderColor =
                    if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
            )
        }
    else
        androidx.compose.material3.OutlinedTextField(
            value,
            onValueChange,
            modifier.then(fieldModifier),
            label = label,
            placeholder = { if (placeholder.isNotEmpty()) BeaconText(placeholder) },
            singleLine = singleLine,
            enabled = enabled,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
        )
}

/**
 * Dropdown rows share one body: the label takes the free space and the current choice is marked
 * with the check icon the other pickers use, never with a "✓ " prefix glued into the text.
 */
@Composable
fun BeaconSelectableRow(
    label: String,
    selected: Boolean,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
    ) {
        if (leading != null) leading()
        BeaconText(label, Modifier.weight(1f), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Box(Modifier.size(20.dp)) {
            if (selected) androidx.compose.material3.Icon(BeaconIcons.Check,
                stringResource(R.string.ui_selected_state_desc), Modifier.size(20.dp))
        }
    }
}
