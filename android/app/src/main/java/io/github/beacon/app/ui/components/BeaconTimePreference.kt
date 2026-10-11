package io.github.beacon.app.ui.components

import android.app.TimePickerDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun BeaconTimePreference(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val latestChange by rememberUpdatedState(onChange)
    BeaconButton(onClick = { open = true }, modifier = modifier, enabled = enabled) {
        BeaconText("$label  $value")
    }
    if (open) {
        DisposableEffect(context) {
            val parts = value.split(":")
            val dialog =
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        latestChange(String.format(Locale.ROOT, "%02d:%02d", hour, minute))
                        open = false
                    },
                    parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    parts.getOrNull(1)?.toIntOrNull() ?: 0,
                    true,
                )
            dialog.setOnDismissListener { open = false }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }
}
