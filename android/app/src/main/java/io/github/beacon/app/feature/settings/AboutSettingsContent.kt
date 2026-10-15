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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.beacon.app.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.beacon.app.R
import io.github.beacon.app.BuildConfig
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
            BeaconText(
                stringResource(R.string.settings_version_label, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    var licenses by rememberSaveable { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var licenseText by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var licenseFailed by remember { mutableStateOf(false) }
    LaunchedEffect(licenses, attempt) {
        if (licenses) {
            licenseText = null
            licenseFailed = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val names = context.assets.list("third-party").orEmpty().filter { it.endsWith(".txt") }.sorted()
                    check(names.isNotEmpty())
                    names.map { name -> name to context.assets.open("third-party/$name").bufferedReader().use { it.readText() } }
                }
            }
            licenseText = result.getOrNull()
            licenseFailed = result.isFailure
        }
    }
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) {
            onOperationDetails(resources.getString(R.string.settings_about_browser_unavailable))
        } catch (_: SecurityException) {
            onOperationDetails(resources.getString(R.string.settings_about_browser_blocked))
        }
    }
    SettingsGroup(stringResource(R.string.ui_v2_project_links)) {
        SettingsRow(stringResource(R.string.settings_about_project), "GitHub", BeaconIcons.Cloud) {
            open("https://github.com/ZeroHomers0/beacon")
        }
        SettingsFieldDivider()
        SettingsRow(stringResource(R.string.ui_v2_docs), "", BeaconIcons.Guide) {
            open("https://github.com/ZeroHomers0/beacon#readme")
        }
        SettingsFieldDivider()
        SettingsRow(stringResource(R.string.ui_v2_licenses), "", BeaconIcons.Info) { licenses = true }
    }
    if (licenses) BeaconAlertDialog(
        onDismissRequest = { licenses = false },
        title = { BeaconText(stringResource(R.string.ui_v2_licenses)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (licenseText == null) {
                    SettingsHint(stringResource(if (licenseFailed) R.string.ui_v2_license_failed else R.string.ui_v2_license_loading))
                    if (licenseFailed) BeaconTextButton(onClick = { attempt++ }) { BeaconText(stringResource(R.string.action_retry)) }
                } else SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        licenseText.orEmpty().forEach { (name, text) ->
                            BeaconText(name, style = MaterialTheme.typography.titleSmall)
                            BeaconText(text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { BeaconButton(onClick = { licenses = false }) { BeaconText(stringResource(R.string.ui_got_it_action)) } },
    )
}
