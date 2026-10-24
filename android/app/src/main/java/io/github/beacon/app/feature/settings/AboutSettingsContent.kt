@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

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
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.R
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.icons.BeaconIcons

internal fun LazyListScope.aboutSettingsItems(
    resources: android.content.res.Resources,
    context: Context,
    onOperationDetails: (String) -> Unit,
) {
    item {
        Column(
            Modifier.fillMaxWidth().padding(vertical = BeaconUiMetrics.Gap12),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.beacon_logo_about),
                stringResource(R.string.settings_about_logo_description),
                Modifier.size(64.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
            )
            BeaconFittedText(
                stringResource(R.string.settings_app_tagline_hint),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.text.selection.SelectionContainer {
            BeaconFittedText(
                stringResource(R.string.settings_version_label, BuildConfig.VERSION_NAME),
                modifier = Modifier.fillMaxWidth(), inspectable = true,
                style = MaterialTheme.typography.bodySmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }
    item { AboutLinks(resources, context, onOperationDetails) }
}

@androidx.compose.runtime.Composable
private fun AboutLinks(
    resources: android.content.res.Resources,
    context: Context,
    onOperationDetails: (String) -> Unit,
) {
    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: android.content.ActivityNotFoundException) {
            onOperationDetails(resources.getString(R.string.settings_about_browser_unavailable))
        } catch (_: SecurityException) {
            onOperationDetails(resources.getString(R.string.settings_about_browser_blocked))
        }
    }
    SettingsGroup(stringResource(R.string.settings_about_links_heading)) {
        SettingsRow(stringResource(R.string.settings_about_project), "", BeaconIcons.Cloud,
            iconTint = MaterialTheme.colorScheme.onSurface, external = true, onClick = {
            open("https://github.com/ZeroHomers0/beacon")
        })
        SettingsRow(stringResource(R.string.ui_v2_docs), "", BeaconIcons.Guide,
            iconTint = MaterialTheme.colorScheme.onSurface, external = true, onClick = {
            open("https://github.com/ZeroHomers0/beacon/blob/main/README.md")
        })
    }
}
