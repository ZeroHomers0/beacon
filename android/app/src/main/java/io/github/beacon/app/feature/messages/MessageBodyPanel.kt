package io.github.beacon.app.feature.messages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.data.remote.RemoteMessageImage
import io.github.beacon.app.domain.policy.MessagePresentation
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun MessageLinksPanel(body: String) {
    val links = remember(body) { MessagePresentation.links(body) }
    val context = LocalContext.current
    var selected by remember(body) { mutableStateOf<String?>(null) }
    var failed by remember(body) { mutableStateOf(false) }
    if (links.isNotEmpty())
        UiSection(stringResource(R.string.message_links)) {
            links.forEach { link ->
                SettingsRow(java.net.URI(link).host, link, BeaconIcons.OpenWeb) {
                    selected = link
                    failed = false
                }
            }
            if (failed) BeaconText(stringResource(R.string.message_link_failed))
        }
    selected?.let { link ->
        BeaconAlertDialog(
            onDismissRequest = { selected = null },
            title = { BeaconText(stringResource(R.string.message_open_link)) },
            text = { BeaconText(link) },
            confirmButton = {
                BeaconButton(
                    onClick = {
                        failed =
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                )
                            }
                                .isFailure
                        selected = null
                    }
                ) {
                    BeaconText(stringResource(R.string.message_open_browser))
                }
            },
            dismissButton = {
                BeaconOutlinedButton(onClick = { selected = null }) {
                    BeaconText(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
fun MessageMediaPanel(metadata: String) {
    val iconPixels = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
    val values = remember(metadata) { runCatching { JSONObject(metadata) }.getOrNull() }
    val image = values?.optString("image").orEmpty()
    val icon = values?.optString("icon").orEmpty()
    var load by remember(metadata) { mutableStateOf(false) }
    var bitmap by remember(metadata) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var avatar by remember(metadata) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var hint by remember(metadata) { mutableStateOf("") }
    // Resolved here: the download callback below is not composable and cannot read resources.
    val loadingText = stringResource(R.string.msg_media_loading)
    val failedText = stringResource(R.string.msg_media_failed)
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        // Drop large UI-only assets in the background. Returning does not restart downloads.
        load = false
        bitmap = null
        avatar = null
        hint = ""
    }
    if (image.isNotEmpty() || icon.isNotEmpty()) {
        if (!load)
            BeaconOutlinedButton(onClick = { load = true }) {
                BeaconText(stringResource(R.string.msg_media_load))
            }
        LaunchedEffect(load, metadata) {
            if (load) {
                hint = loadingText
                val images =
                    withContext(Dispatchers.IO) {
                        (if (image.isNotEmpty()) RemoteMessageImage.load(image) else null) to
                            (if (icon.isNotEmpty())
                                RemoteMessageImage.load(icon, iconPixels.coerceIn(1, 1024))
                            else null)
                    }
                bitmap = images.first
                avatar = images.second
                hint = if (bitmap == null && avatar == null) failedText else ""
            }
        }
        avatar?.let {
            Image(
                it.asImageBitmap(),
                stringResource(R.string.msg_media_icon_description),
                Modifier.size(48.dp),
            )
        }
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                stringResource(R.string.msg_media_image_description),
                Modifier.fillMaxWidth().heightIn(max = 320.dp),
            )
        }
        if (hint.isNotEmpty()) BeaconText(hint)
    }
}
