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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun detailTime(
    time: Long,
    missing: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    if (time <= 0) missing
    else
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(zone)
            .format(Instant.ofEpochMilli(time))

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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BeaconCard(
                Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    if (message.subtitle.isNotBlank())
                        SelectionContainer {
                            BeaconText(
                                message.subtitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    SelectionContainer {
                        BeaconText(
                            message.body.ifBlank {
                                stringResource(R.string.msg_detail_no_body_hint)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MessageMediaPanel(message.bark)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BeaconText(
                            detailTime(message.createdAtMs, missing),
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
            MessageLinksPanel(message.body + if (barkUrl.isNotBlank()) "\n$barkUrl" else "")
            UiSection(stringResource(R.string.msg_detail_info_title)) {
                DiagnosticRow(
                    stringResource(R.string.msg_detail_reminder_state_label),
                    stringResource(
                        if (message.reminderPending) R.string.msg_detail_reminder_pending
                        else R.string.msg_detail_reminder_none
                    ),
                )
                BeaconText(
                    stringResource(R.string.msg_detail_reminder_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                DiagnosticRow(
                    stringResource(R.string.msg_detail_sent_time_label),
                    detailTime(message.createdAtMs, missing),
                )
                DiagnosticRow(
                    stringResource(R.string.msg_detail_received_time_label),
                    detailTime(message.receivedAtMs, missing),
                )
                BeaconText(
                    stringResource(R.string.msg_detail_timezone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BeaconTextButton(onClick = { rawInfo = !rawInfo }) {
                    BeaconText(
                        stringResource(
                            if (rawInfo) R.string.msg_detail_collapse_raw_action
                            else R.string.msg_detail_expand_raw_action
                        )
                    )
                }
                if (rawInfo)
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
