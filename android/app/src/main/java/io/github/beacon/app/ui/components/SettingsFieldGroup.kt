package io.github.beacon.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Related fields share their enclosing card; only the boundaries between rows are drawn. */
@Composable
fun SettingsFieldGroup(vararg rows: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(
                Modifier.fillMaxWidth(),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            row()
        }
    }
}
