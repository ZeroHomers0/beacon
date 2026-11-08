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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    topBar: @Composable (@Composable () -> Unit) -> Unit = { menu ->
        BeaconTopBar(stringResource(R.string.ui_message_detail_title), compact = true,
            behavior = rememberBeaconBarBehavior(), navigationIcon = {}, actions = { menu() })
    },
) {
    key(MessageKey(message.server, message.id)) {
        var raw by rememberSaveable { mutableStateOf(false) }
        var rawInfo by rememberSaveable { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var muteApplication by rememberSaveable { mutableStateOf(false) }
        var shareFailed by remember { mutableStateOf(false) }
        if(muteApplication&&message.applicationId.isNotBlank())io.github.beacon.app.feature.settings.TemporaryMuteDialog(
            io.github.beacon.app.domain.model.MuteScope(io.github.beacon.app.domain.model.MuteKind.APPLICATION,message.server,message.applicationId),
            stringResource(R.string.mute_application)){muteApplication=false}
        val missing = stringResource(R.string.msg_detail_not_provided_label)
        val menuContent: @Composable () -> Unit = {
            Box {
                BeaconIconButton(
                    enabled = !busy,
                    modifier = Modifier.size(BeaconUiMetrics.ControlMinHeight),
                    onClick = { menu = true },
                ) {
                    Icon(
                        BeaconIcons.More,
                        stringResource(R.string.msg_detail_more_action),
                        Modifier.size(BeaconUiMetrics.IconMedium),
                        tint =
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (busy) 0.38f else 1f
                            ),
                    )
                }
                BeaconDropdownMenu(menu && !busy, { menu = false }) {
                    if (message.body.isNotBlank()) BeaconDropdownMenuItem({
                        BeaconText(stringResource(if (raw) R.string.detail_view_rendered else R.string.detail_view_source))
                    }) { menu = false; raw = !raw }
                    if(message.applicationId.isNotBlank())BeaconDropdownMenuItem(
                        {BeaconText(stringResource(R.string.mute_application))}){menu=false;muteApplication=true}
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
        Box(modifier) {
            BeaconScaffold(topBar = { topBar(menuContent) }, bottomBar = {}) { contentInsets ->
                Column(
                    Modifier.fillMaxSize().padding(contentInsets)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.widthIn(max = BeaconDetailText.MaxWidth).fillMaxWidth()
                            .padding(horizontal = BeaconDetailText.PageInset,
                                vertical = BeaconDetailText.SectionGap)
                            .padding(bottom = BeaconDetailText.PageBottom - BeaconDetailText.SectionGap),
                        verticalArrangement = Arrangement.spacedBy(BeaconDetailText.SectionGap),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().testTag("message-detail-header"),
                            verticalArrangement = Arrangement.spacedBy(BeaconDetailText.ParagraphGap),
                        ) {
                            SelectionContainer {
                                BeaconText(message.title.ifBlank { stringResource(R.string.msg_untitled_message) },
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontSize = BeaconDetailText.TitleSize,
                                        lineHeight = BeaconDetailText.TitleLineHeight,
                                        fontWeight = FontWeight.SemiBold))
                            }
                            if (message.subtitle.isNotBlank()) SelectionContainer {
                                BeaconText(message.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = BeaconDetailText.SubtitleSize,
                                        lineHeight = BeaconDetailText.SubtitleLineHeight))
                            }
                            val source = message.applicationName.ifBlank {
                                if (message.applicationId.isNotBlank()) stringResource(
                                    R.string.message_application_short, message.applicationId.take(8)) else ""
                            }
                            val timestamp = if (message.createdAtMs > 0) message.createdAtMs else message.receivedAtMs
                            val metadataStyle = MaterialTheme.typography.bodySmall.copy(
                                fontSize = BeaconDetailText.MetadataSize, lineHeight = BeaconDetailText.MetadataLineHeight)
                            if (source.isNotBlank() || timestamp > 0) FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6),
                                verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4),
                            ) {
                                if (source.isNotBlank()) BeaconText(source, style = metadataStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (timestamp > 0) BeaconText(
                                    (if (source.isNotBlank()) "· " else "") + stringResource(
                                        if (message.createdAtMs > 0) R.string.msg_detail_sent_time_label
                                        else R.string.msg_detail_received_time_label) + " " + detailTime(timestamp, missing),
                                    style = metadataStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (message.group.isNotBlank()) BeaconText(message.group,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val hasBlocks = remember(message.structuredBlocks) {
                            runCatching { org.json.JSONArray(message.structuredBlocks).length() > 0 }.getOrDefault(false)
                        }
                        if (message.body.isNotBlank() || !hasBlocks) Column(
                            Modifier.fillMaxWidth().testTag("message-detail-body"),
                        ) { MessageMarkdownBody(message.body, raw) }
                        if (hasBlocks) BeaconCard(Modifier.fillMaxWidth().testTag("message-detail-data"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(BeaconUiMetrics.CardInset)) {
                                StructuredBlocksPanel(message.structuredBlocks)
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
                        MessageActionsPanel(message.actions, barkUrl, grouped = false)
                        Row(
                            Modifier.fillMaxWidth().testTag("message-detail-raw-header"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
                        ) {
                            BeaconText(stringResource(R.string.msg_detail_raw_heading), Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
    }
}
