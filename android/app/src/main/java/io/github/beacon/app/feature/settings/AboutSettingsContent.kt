@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.BeaconActionButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.DiagnosticRow
import io.github.beacon.app.ui.components.SettingsHint
import io.github.beacon.app.ui.components.UiSection
import io.github.beacon.app.ui.icons.BeaconIcons

internal fun LazyListScope.aboutSettingsItems(
    resources: android.content.res.Resources,
    context: Context,
    onOperationDetails: (String) -> Unit,
) {
    item {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.beacon_logo_about),
                stringResource(R.string.settings_about_logo_description),
                Modifier.size(64.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
            )
            BeaconText("Beacon", style = MaterialTheme.typography.headlineMedium)
            BeaconText(
                stringResource(R.string.settings_app_tagline_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BeaconText(
                BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item {
        // Every entry here is a labelled fact; the tagline above the card carries the marketing
        // line, so the card title stays a heading instead of a sentence.
        UiSection(stringResource(R.string.settings_current_support_title)) {
            SettingsHint(stringResource(R.string.settings_receive_benefit_hint))
            DiagnosticRow(
                resources.getString(R.string.settings_about_transport_title),
                resources.getString(R.string.settings_about_transport_desc),
            )
            DiagnosticRow(
                resources.getString(R.string.ui_power_saving_mode),
                resources.getString(R.string.settings_about_background_desc),
            )
            DiagnosticRow(
                resources.getString(R.string.settings_about_build),
                BuildConfig.VERSION_CODE.toString(),
            )
            SettingsHint(resources.getString(R.string.settings_about_receive_hint))
            BeaconActionButton(
                resources.getString(R.string.settings_about_project),
                BeaconIcons.Cloud,
                Modifier.fillMaxWidth(),
            ) {
                try {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/ZeroHomers0/beacon"),
                        )
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    onOperationDetails(
                        resources.getString(R.string.settings_about_browser_unavailable)
                    )
                } catch (_: SecurityException) {
                    onOperationDetails(resources.getString(R.string.settings_about_browser_blocked))
                }
            }
        }
    }
}
