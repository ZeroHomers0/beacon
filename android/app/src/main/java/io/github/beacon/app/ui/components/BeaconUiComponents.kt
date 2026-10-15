@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.beacon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.ui.icons.BeaconIcons
import io.github.beacon.app.ui.theme.LocalBeaconUiStyle

/**
 * Page titles are resource ids: the enum itself is not composable, so callers resolve the title
 * where they render it.
 */
enum class SettingsScreen(val titleRes: Int) {
    HOME(R.string.ui_nav_settings_title),
    CONNECTION(R.string.settings_connection_section_title),
    RECEPTION(R.string.settings_reception_page_title),
    NOTIFICATIONS(R.string.settings_notifications_section_title),
    NOTIFICATION_RULES(R.string.quiet_title),
    BACKGROUND(R.string.settings_background_permissions_title),
    // Restore legacy saved state without exposing a second appearance page.
    APPEARANCE(R.string.settings_appearance_section_desc),
    DIAGNOSTICS(R.string.settings_advanced_section_title),
    ABOUT(R.string.settings_about_title),
    STORAGE(R.string.settings_storage_title),
    BACKUP(R.string.settings_backup_title),
    GUIDE(R.string.settings_guide_title),
    SERVER_ADMIN(R.string.admin_title),
}

/**
 * Notification permission as state, not as display text. The settings page previously decided
 * whether notifications were allowed by running `startsWith` over the sentence it displayed.
 */
enum class NotificationPermissionState {
    UNKNOWN,
    ALLOWED,
    DENIED,
}

@Composable
fun ActionFlow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
fun UiSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    BeaconCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(BeaconUiMetrics.CardRadius),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(BeaconUiMetrics.CardInset), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap)) {
            BeaconText(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Secondary explanations share a readable, quiet treatment across both settings styles. */
@Composable
fun SettingsHint(text: String) {
    BeaconText(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BeaconText(
            title,
            Modifier.padding(horizontal = BeaconUiMetrics.CardInset),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BeaconCard(
            Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
            content = content,
        )
    }
}

/**
 * The scroll container every settings surface uses: item spacing and keyboard avoidance are fixed
 * here so no page can drift away from the others.
 */
@Composable
fun BeaconSettingsList(
    state: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier.imePadding(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SettingsRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) =
    SettingsRow(title, subtitle, icon, MaterialTheme.colorScheme.onSurface, onClick)

@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    badge: Boolean = false,
) {
    if (LocalBeaconUiStyle.current == BeaconUiStyle.MIUIX) {
        top.yukonga.miuix.kmp.extra.SuperArrow(
            title,
            summary = subtitle.takeIf { it.isNotBlank() },
            onClick = onClick,
            startAction = {
                BadgedIcon(icon, iconTint, badge, Modifier.padding(end = 8.dp))
            },
        )
    } else {
        Surface(onClick = onClick, color = Color.Transparent) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BadgedIcon(icon, iconTint, badge)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BeaconText(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle.isNotBlank())
                        BeaconText(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                Icon(BeaconIcons.Arrow, null)
            }
        }
    }
}

/**
 * The leading icon with an optional dot marking something new on this row.
 *
 * The dot sits inside the icon's own bounds, so it needs no extra space and cannot be clipped by
 * either style's row.
 */
@Composable
private fun BadgedIcon(
    icon: ImageVector,
    tint: Color,
    badge: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(24.dp)) {
        Icon(icon, null, tint = tint)
        if (badge)
            Box(
                Modifier.align(Alignment.TopEnd)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
    }
}

@Composable
fun MessageItem(
    message: IncomingMessage,
    selectionMode: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onCopy: (Boolean) -> Unit,
    onDelete: () -> Unit,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggleRead: () -> Unit,
    onLongPress: () -> Unit,
    onShare: (() -> Unit)? = null,
    onRemind: (() -> Unit)? = null,
    onFilterGroup: (() -> Unit)? = null,
    onFilterApplication: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val unreadLabel = stringResource(R.string.msg_unread_label)
    val longPressLabel = stringResource(R.string.ui_message_long_press)
    BeaconCard(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(BeaconUiMetrics.CardRadius))
                .combinedClickable(
                    enabled = !busy,
                    onLongClickLabel = longPressLabel,
                    onClick = { if (selectionMode) onSelect(!selected) else onOpen() },
                    onLongClick = {
                        onLongPress()
                    },
                ),
        shape = RoundedCornerShape(BeaconUiMetrics.CardRadius),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
            ),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (selectionMode)
                    BeaconCheckbox(
                        selected,
                        onSelect,
                        enabled = !busy,
                        contentDescription = message.title,
                    )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    BeaconText(
                        message.title.ifBlank { stringResource(R.string.msg_untitled_message) },
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (message.group.isNotBlank() || message.applicationId.isNotBlank())
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (message.applicationId.isNotBlank())
                                MessageFilterTag(
                                    label = message.applicationName.ifBlank {
                                        stringResource(
                                            R.string.message_application_short,
                                            message.applicationId.take(8),
                                        )
                                    },
                                    enabled = !busy && !selectionMode && onFilterApplication != null,
                                    onClick = { onFilterApplication?.invoke() },
                                )
                            if (message.group.isNotBlank())
                                MessageFilterTag(
                                    label = message.group,
                                    enabled = !busy && !selectionMode && onFilterGroup != null,
                                    onClick = { onFilterGroup?.invoke() },
                                )
                        }
                }
                if (!message.isRead)
                    Box(
                        Modifier.size(6.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                androidx.compose.foundation.shape.CircleShape,
                            )
                            .semantics { contentDescription = unreadLabel }
                    )
                if (!selectionMode)
                    Box {
                        BeaconIconButton(enabled = !busy, onClick = { menu = true }) {
                            Icon(BeaconIcons.More, stringResource(R.string.ui_message_more_action))
                        }
                        BeaconDropdownMenu(menu && !busy, { menu = false }) {
                            MessageMenuItems(
                                message.isRead,
                                onShare =
                                    onShare?.let { action ->
                                        {
                                            menu = false
                                            action()
                                        }
                                    },
                                onRemind =
                                    onRemind?.let { action ->
                                        {
                                            menu = false
                                            action()
                                        }
                                    },
                                onToggleRead = {
                                    menu = false
                                    onToggleRead()
                                },
                                onCopy = { body ->
                                    menu = false
                                    onCopy(body)
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
                BeaconText(
                    message.subtitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            BeaconText(
                remember(message.body) {
                    io.github.beacon.app.domain.policy.MessageMarkdown.summary(message.body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BeaconText(
                    formatMessageTime(message.createdAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (message.reminderPending)
                    BeaconText(
                        stringResource(R.string.msg_reminder_pending_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
            }
        }
    }
}

/** Compact metadata with a full touch target, positioned directly beneath the title. */
@Composable
internal fun MessageFilterTag(
    label: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier.heightIn(min = if (onClick == null) 32.dp else BeaconUiMetrics.ControlMinHeight)
            .widthIn(min = if (onClick == null) 0.dp else BeaconUiMetrics.ControlMinHeight)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(
                    enabled = enabled,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onClick,
                )
            )
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BeaconText(
            label,
            Modifier.background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
