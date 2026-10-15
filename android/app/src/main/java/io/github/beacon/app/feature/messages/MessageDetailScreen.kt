@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.beacon.app.feature.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import java.time.ZoneId

internal fun detailTime(
    time: Long,
    missing: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    if (time <= 0) missing
    else
        io.github.beacon.app.domain.policy.DisplayTime.format(time, zone)

@Composable
fun MessageDetailScreen(
    message: IncomingMessage,
    modifier: Modifier,
    onCopy: (Boolean) -> Unit,
    onDelete: () -> Unit,
    busy: Boolean,
    onToggleRead: () -> Unit,
    onShare: (() -> Unit)? = null,
    onRemind: (() -> Unit)? = null,
) {
    key(MessageKey(message.server, message.id)) {
        var rawInfo by rememberSaveable { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var shareFailed by remember { mutableStateOf(false) }
        val missing = stringResource(R.string.msg_detail_not_provided_label)
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BeaconUiMetrics.PageInset, vertical = BeaconUiMetrics.SectionGap),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BeaconCard(
                Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
            ) {
                Column(Modifier.padding(BeaconUiMetrics.CardInset), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap)) {
                    Row(verticalAlignment = Alignment.Top) {
                        SelectionContainer(Modifier.weight(1f)) {
                            BeaconText(
                                message.title.ifBlank {
                                    stringResource(R.string.msg_untitled_message)
                                },
                                style =
                                    MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                            )
                        }
                        Box {
                            BeaconActionIcon(
                                BeaconIcons.More,
                                stringResource(R.string.msg_detail_more_action),
                                enabled = !busy,
                            ) {
                                menu = true
                            }
                            BeaconDropdownMenu(menu && !busy, { menu = false }) {
                                MessageMenuItems(
                                    message.isRead,
                                    onToggleRead = {
                                        menu = false
                                        onToggleRead()
                                    },
                                    onCopy = { body ->
                                        menu = false
                                        onCopy(body)
                                    },
                                    onShare =
                                        onShare?.let { action ->
                                            {
                                                menu = false
                                                shareFailed = runCatching { action() }.isFailure
                                            }
                                        },
                                    onRemind =
                                        onRemind?.let { action ->
                                            {
                                                menu = false
                                                action()
                                            }
                                        },
                                    onDelete = {
                                        menu = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                    if (message.group.isNotBlank() || message.applicationId.isNotBlank())
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (message.applicationId.isNotBlank())
                                MessageFilterTag(
                                    message.applicationName.ifBlank {
                                        stringResource(
                                            R.string.message_application_short,
                                            message.applicationId.take(8),
                                        )
                                    }
                                )
                            if (message.group.isNotBlank()) MessageFilterTag(message.group)
                        }
                    if (message.subtitle.isNotBlank())
                        SelectionContainer {
                            BeaconText(
                                message.subtitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    MessageMarkdownBody(message.body)
                    val hasMedia = remember(message.bark) {
                        runCatching { org.json.JSONObject(message.bark) }.getOrNull()?.let {
                            it.optString("image").isNotBlank() || it.optString("icon").isNotBlank()
                        } == true
                    }
                    if (hasMedia) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MessageMediaPanel(message.bark)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BeaconText(
                            stringResource(R.string.msg_detail_sent_time_label) + " · " + detailTime(message.createdAtMs, missing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (shareFailed)
                BeaconText(
                    stringResource(R.string.message_share_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            val barkUrl = runCatching {
                org.json.JSONObject(message.bark).optString("url")
            }
                .getOrDefault("")
            MessageActionsPanel(message.actions, barkUrl)
            BeaconCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    Modifier.padding(BeaconUiMetrics.CardInset),
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap),
                ) {
                    BeaconTextButton(onClick = { rawInfo = !rawInfo }) {
                        BeaconText(stringResource(
                            if (rawInfo) R.string.msg_detail_collapse_raw_action
                            else R.string.msg_detail_expand_raw_action
                        ))
                    }
                    SettingsHint(stringResource(R.string.msg_detail_reminder_hint))
                    SettingsHint(stringResource(R.string.msg_detail_timezone_hint))
                    if (rawInfo) {
                        SettingsFieldGroup(
                            {
                                DiagnosticRow(
                                    stringResource(R.string.msg_detail_reminder_state_label),
                                    stringResource(
                                        if (message.reminderPending) R.string.msg_detail_reminder_pending
                                        else R.string.msg_detail_reminder_none
                                    ),
                                )
                            },
                            {
                                DiagnosticRow(
                                    stringResource(R.string.msg_detail_sent_time_label),
                                    detailTime(message.createdAtMs, missing),
                                )
                            },
                            {
                                DiagnosticRow(
                                    stringResource(R.string.msg_detail_received_time_label),
                                    detailTime(message.receivedAtMs, missing),
                                )
                            },
                        )
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                BeaconText(
                                    stringResource(R.string.msg_detail_id_desc, message.id),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                BeaconText(
                                    stringResource(R.string.msg_detail_source_desc, message.server),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
