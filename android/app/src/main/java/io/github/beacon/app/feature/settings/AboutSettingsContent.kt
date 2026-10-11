@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.BeaconActionButton
import io.github.beacon.app.ui.components.BeaconText
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
        }
    }
    item {
        // The page stays deliberately short: identity and the project link. Anything
        // else belongs to the settings page that owns the feature.
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
                onOperationDetails(resources.getString(R.string.settings_about_browser_unavailable))
            } catch (_: SecurityException) {
                onOperationDetails(resources.getString(R.string.settings_about_browser_blocked))
            }
        }
    }
}
