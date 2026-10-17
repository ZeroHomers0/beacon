package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SettingsFieldGroup(vararg rows: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            row()
        }
    }
}

