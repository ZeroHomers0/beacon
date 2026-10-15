package io.github.beacon.app.feature.messages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.data.remote.MessageActionsCodec
import io.github.beacon.app.domain.model.MessageLinkAction
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

/**
 * Explicit user clicks open destinations; receiving or displaying a message never executes them.
 */
@Composable
fun MessageActionsPanel(actions: List<MessageLinkAction>, legacyUrl: String = "") {
    val context = LocalContext.current
    val defaultTitle = stringResource(R.string.message_action_open)
    val available =
        remember(actions, legacyUrl, defaultTitle) {
            val supplied = actions.ifEmpty {
                if (legacyUrl.isBlank()) emptyList()
                else listOf(MessageLinkAction(defaultTitle, legacyUrl))
            }
            runCatching {
                    MessageActionsCodec.validate(supplied)
                    supplied
                }
                .getOrDefault(emptyList())
        }
    var failed by remember(available) { mutableStateOf(false) }
    if (available.isNotEmpty()) {
        UiSection(stringResource(R.string.message_actions)) {
            available.forEach { action ->
                BeaconOutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        failed =
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(action.url).normalizeScheme())
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                )
                            }
                                .isFailure
                    },
                ) {
                    Icon(BeaconIcons.OpenWeb, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    BeaconText(action.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                BeaconText(
                    action.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (failed)
                BeaconText(
                    stringResource(R.string.message_link_failed),
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
}
