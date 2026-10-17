package io.github.beacon.app.feature.messages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.data.remote.RemoteMessageImage
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun MessageLinksPanel(links: List<String>) {
    var selected by remember(links) { mutableStateOf<String?>(null) }
    if (links.isNotEmpty())
        UiSection(stringResource(R.string.message_links)) {
            links.forEach { link ->
                SettingsRow(java.net.URI(link).host, link, BeaconIcons.OpenWeb) {
                    selected = link
                }
            }
        }
    selected?.let { link -> MessageLinkDialog(link) { selected = null } }
}

@Composable
internal fun MessageLinkDialog(link: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var failed by remember(link) { mutableStateOf(false) }
    BeaconAlertDialog(
        onDismissRequest = onDismiss,
        title = { BeaconText(stringResource(R.string.message_open_link)) },
        text = {
            Column {
                BeaconText(link)
                if (failed) BeaconText(stringResource(R.string.message_link_failed))
            }
        },
        confirmButton = {
            BeaconButton(onClick = {
                failed = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(link))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                    )
                }.isFailure
                if (!failed) onDismiss()
            }) {
                BeaconText(stringResource(R.string.message_open_browser))
            }
        },
        dismissButton = { BeaconCancelButton(onClick = onDismiss) },
    )
}

@Composable
fun MessageMediaPanel(metadata: String, description: String? = null) {
    val iconPixels = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
    val values = remember(metadata) { runCatching { JSONObject(metadata) }.getOrNull() }
    val image = values?.optString("image").orEmpty()
    val icon = values?.optString("icon").orEmpty()
    var load by remember(metadata) { mutableStateOf(false) }
    var requested by rememberSaveable(metadata) { mutableStateOf(false) }
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
            BeaconOutlinedButton(
                onClick = { requested = true; load = true },
                enabled = !load,
            ) {
                BeaconText(
                    if (description == null) stringResource(R.string.msg_media_load)
                    else stringResource(R.string.message_markdown_load_image, description)
                )
            }
        LaunchedEffect(load, metadata) {
            if (load) {
                hint = loadingText
                val images =
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            val photo = async {
                                if (image.isNotEmpty()) RemoteMessageImage.load(image) else null
                            }
                            val avatarImage = async {
                                if (icon.isNotEmpty())
                                    RemoteMessageImage.load(icon, iconPixels.coerceIn(1, 1024))
                                else null
                            }
                            photo.await() to avatarImage.await()
                        }
                    }
                bitmap = images.first
                avatar = images.second
                hint = if (bitmap == null && avatar == null) failedText else ""
                load = false
            }
        }
        if (requested) androidx.compose.material3.Surface(
            modifier = if (image.isNotEmpty()) Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                else Modifier.fillMaxWidth().height(64.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        description ?: stringResource(R.string.msg_media_image_description),
                        Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                avatar?.let {
                    Image(
                        it.asImageBitmap(),
                        stringResource(R.string.msg_media_icon_description),
                        Modifier.align(Alignment.TopStart).padding(8.dp).size(48.dp),
                    )
                }
                if (bitmap == null && avatar == null) BeaconText(
                    hint.ifBlank { stringResource(R.string.msg_media_load) },
                    Modifier.padding(BeaconUiMetrics.CardInset),
                )
            }
        }
    }
}
