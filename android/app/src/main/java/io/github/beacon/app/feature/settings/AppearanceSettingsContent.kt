package io.github.beacon.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import io.github.beacon.app.ui.icons.BeaconIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.ui.components.*

@Composable
internal fun AppearanceSettingsContent(
    style: BeaconUiStyle,
    mode: BeaconThemeMode,
    onStyle: (BeaconUiStyle) -> Unit,
    onMode: (BeaconThemeMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap)) {
        SettingsGroup(stringResource(R.string.appearance_style)) {
            Column(Modifier.selectableGroup()) {
                BeaconUiStyle.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = BeaconUiMetrics.ControlMinHeight)
                            .selectable(style == option, role = Role.RadioButton, onClick = { onStyle(option) })
                            .padding(BeaconUiMetrics.CardInset),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BeaconText(if (option == BeaconUiStyle.MIUIX) "MIUIX" else "Material 3",
                                style = MaterialTheme.typography.bodyLarge)
                            SettingsHint(stringResource(if (option == BeaconUiStyle.MIUIX)
                                R.string.ui_v2_miuix_hint else R.string.ui_v2_material_hint))
                        }
                        if (style == option) Icon(BeaconIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        SettingsSection(stringResource(R.string.settings_appearance_section_desc)) {
            BeaconDropdownPreference(
                stringResource(R.string.appearance_theme),
                listOf(stringResource(R.string.settings_style_system_label),
                    stringResource(R.string.settings_style_light_label),
                    stringResource(R.string.settings_style_dark_label)),
                mode.ordinal,
            ) { onMode(BeaconThemeMode.entries[it]) }
            SettingsHint(stringResource(R.string.ui_v2_appearance_hint))
        }
    }
}
