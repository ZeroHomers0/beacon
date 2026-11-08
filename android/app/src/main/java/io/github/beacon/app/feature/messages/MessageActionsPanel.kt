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
import io.github.beacon.app.domain.model.MessageLinkAction
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons

/**
 * Explicit user clicks open destinations; receiving or displaying a message never executes them.
 */
@Composable
fun MessageActionsPanel(actions: List<MessageLinkAction>, legacyUrl: String = "", grouped: Boolean = true) {
    val context = LocalContext.current
    val defaultTitle = stringResource(R.string.message_action_open)
    val available =
        remember(actions, legacyUrl, defaultTitle) {
            val supplied = actions.ifEmpty {
                if (legacyUrl.isBlank()) emptyList()
                else listOf(MessageLinkAction(defaultTitle, legacyUrl))
            }
            runCatching {
                    io.github.beacon.app.domain.policy.MessageActionValidation.validate(supplied)
                    supplied.take(1)
                }
                .getOrDefault(emptyList())
        }
    var failed by remember(available) { mutableStateOf(false) }
    if (available.isNotEmpty()) {
        val content: @Composable () -> Unit = {
            available.forEach { action ->
                BeaconOutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        failed =
                            runCatching {
                                context.startActivity(
                                    Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(action.url).normalizeScheme(),
                                        )
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                )
                            }
                                .isFailure
                    },
                ) {
                    Icon(BeaconIcons.OpenWeb, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    BeaconText(action.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BeaconText(
                    if (grouped) action.url else java.net.URI(action.url).host.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (grouped) UiSection(stringResource(R.string.message_actions)) { content() }
        else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) { content() }
        if (failed) BeaconFailureDialog(stringResource(R.string.message_link_failed)) { failed = false }
    }
}
