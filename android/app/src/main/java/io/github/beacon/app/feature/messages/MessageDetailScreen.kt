package io.github.beacon.app.feature.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.policy.TAG_SLOT_APPLICATION
import io.github.beacon.app.domain.policy.TAG_SLOT_GROUP
import io.github.beacon.app.ui.components.*
import io.github.beacon.app.ui.icons.BeaconIcons
import java.time.ZoneId

/** Low-contrast divider between the title and metadata within the header card. */
@Composable
private fun DetailRule() {
    HorizontalDivider(
        thickness = BeaconDetailText.DividerThickness,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

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
        var raw by rememberSaveable { mutableStateOf(false) }
        var rawInfo by rememberSaveable { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var shareFailed by remember { mutableStateOf(false) }
        val missing = stringResource(R.string.msg_detail_not_provided_label)
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = BeaconUiMetrics.PageInset,
                        end = BeaconUiMetrics.PageInset,
                        top = BeaconUiMetrics.SectionGap,
                        bottom = BeaconDetailText.PageBottom,
                    )
                ),
            verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
        ) {
            BeaconCard(
                Modifier.fillMaxWidth().testTag("message-detail-header"),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
            ) {
                Column(
                    Modifier.padding(BeaconUiMetrics.CardInset),
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap10),
                ) {
                    // Header content has its own card; align actions with the first title line.
                    Row(verticalAlignment = Alignment.Top) {
                        SelectionContainer(Modifier.weight(1f)) {
                            BeaconText(
                                message.title.ifBlank {
                                    stringResource(R.string.msg_untitled_message)
                                },
                                style =
                                    MaterialTheme.typography.headlineSmall.copy(
                                        fontSize = BeaconDetailText.TitleSize,
                                        lineHeight = BeaconDetailText.TitleLineHeight,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                            )
                        }
                        FirstLineSlot(BeaconDetailText.TitleLineHeight) {
                            Box {
                                BeaconIconButton(
                                    enabled = !busy,
                                    modifier = Modifier.size(BeaconUiMetrics.MessageCardActionSize),
                                    onClick = { menu = true },
                                ) {
                                    Icon(
                                        BeaconIcons.More,
                                        stringResource(R.string.msg_detail_more_action),
                                        Modifier.size(BeaconUiMetrics.MessageCardActionIconSize),
                                        tint =
                                            MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = if (busy) 0.38f else 1f
                                            ),
                                    )
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
                                                    shareFailed =
                                                        runCatching { action() }.isFailure
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
                    }
                    if (message.subtitle.isNotBlank()) SelectionContainer {
                        BeaconText(
                            message.subtitle,
                            style =
                                MaterialTheme.typography.titleSmall.copy(
                                    fontSize = BeaconDetailText.SubtitleSize,
                                    lineHeight = BeaconDetailText.SubtitleLineHeight,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val canToggle = message.body.isNotBlank()
                    val hasMetadata =
                        message.group.isNotBlank() ||
                            message.applicationId.isNotBlank() ||
                            canToggle
                    if (hasMetadata) {
                        DetailRule()
                        MessageMetadataRow(hasAction = canToggle) {
                            if (message.applicationId.isNotBlank()) {
                                MessageFilterTag(
                                    message.applicationName.ifBlank {
                                        stringResource(
                                            R.string.message_application_short,
                                            message.applicationId.take(8),
                                        )
                                    },
                                    toneSlot = TAG_SLOT_APPLICATION,
                                )
                            }
                            if (message.group.isNotBlank()) {
                                MessageFilterTag(message.group, toneSlot = TAG_SLOT_GROUP)
                            }
                            if (canToggle) {
                                MessageFilterTag(
                                    stringResource(
                                        if (raw) R.string.message_markdown_rendered
                                        else R.string.message_markdown_source
                                    ),
                                    outlined = true,
                                    onClick = { raw = !raw },
                                )
                            }
                        }
                    }
                }
            }
            BeaconCard(
                Modifier.fillMaxWidth().testTag("message-detail-body"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    Modifier.padding(BeaconUiMetrics.CardInset),
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap10),
                ) {
                    MessageMarkdownBody(message.body, raw)
                    StructuredBlocksPanel(message.structuredBlocks)
                    BeaconText(
                        stringResource(R.string.msg_detail_sent_time_label) + " · " +
                            detailTime(message.createdAtMs, missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val hasMedia = remember(message.bark) {
                runCatching { org.json.JSONObject(message.bark) }.getOrNull()?.let {
                    it.optString("image").isNotBlank() || it.optString("icon").isNotBlank()
                } == true
            }
            if (hasMedia) {
                MessageMediaPanel(message.bark)
            }
            if (shareFailed) BeaconFailureDialog(stringResource(R.string.message_share_failed)) { shareFailed = false }
            val barkUrl = runCatching {
                org.json.JSONObject(message.bark).optString("url")
            }
                .getOrDefault("")
            MessageActionsPanel(message.actions, barkUrl)
            Row(
                Modifier.fillMaxWidth().testTag("message-detail-raw-header"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
            ) {
                SettingsLabel(stringResource(R.string.msg_detail_raw_heading), Modifier.weight(1f))
                BeaconIconButton(
                    onClick = { rawInfo = !rawInfo },
                    modifier = Modifier.size(BeaconUiMetrics.ControlMinHeight),
                ) {
                    Icon(
                        BeaconIcons.Arrow,
                        contentDescription = stringResource(
                            if (rawInfo) R.string.msg_detail_collapse_raw_action
                            else R.string.msg_detail_expand_raw_action
                        ),
                        modifier = Modifier.size(BeaconUiMetrics.IconMedium)
                            .rotate(if (rawInfo) 270f else 90f),
                    )
                }
            }
            if (rawInfo) BeaconCard(
                Modifier.fillMaxWidth().testTag("message-detail-raw-content"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    Modifier.padding(BeaconUiMetrics.CardInset),
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap),
                ) {
                    SettingsHint(stringResource(R.string.msg_detail_reminder_hint))
                    SettingsHint(stringResource(R.string.msg_detail_timezone_hint))
                    SettingsFieldGroup(
                        {
                            DiagnosticRow(stringResource(R.string.ui_v2_priority), message.priority.toString())
                        },
                        {
                            DiagnosticRow(stringResource(R.string.ui_v2_read_state),
                                stringResource(if (message.isRead) R.string.msg_read_label else R.string.msg_unread_label))
                        },
                        {
                            DiagnosticRow(stringResource(R.string.ui_v2_expiry), detailTime(message.expiresAtMs, missing))
                        },
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
                        Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
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
