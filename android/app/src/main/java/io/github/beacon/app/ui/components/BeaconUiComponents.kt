@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.beacon.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.ui.icons.BeaconIcons

/**
 * Page titles are resource ids: the enum itself is not composable, so callers resolve the title
 * where they render it.
 */
enum class SettingsScreen(val titleRes: Int) {
    HOME(R.string.ui_nav_settings_title),
    CONNECTION(R.string.ui_v2_server_config_title),
    RECEPTION(R.string.settings_reception_page_title),
    NOTIFICATIONS(R.string.settings_notifications_section_title),
    NOTIFICATION_RULES(R.string.quiet_title),
    BACKGROUND(R.string.settings_background_permissions_title),
    APPEARANCE(R.string.ui_nav_settings_title), // Legacy saved route; content now resolves to HOME.
    ABOUT(R.string.settings_about_title),
    STORAGE(R.string.settings_storage_title),
    BACKUP(R.string.settings_backup_title),
    GUIDE(R.string.settings_guide_title),
    SERVER_ADMIN(R.string.admin_title),
    SERVER_DEVICES(R.string.admin_devices_title),
    SERVER_APPLICATIONS(R.string.admin_applications_title),
    SERVER_TEST(R.string.admin_test_title_page),
    QUIET_HOURS(R.string.quiet_schedule),
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
        horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4),
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
        Column(
            Modifier.padding(BeaconUiMetrics.CardInset),
            verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap),
        ) {
            BeaconText(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Settings use a common outside title and inset content, distinct from message sections. */
@Composable
fun SettingsSection(title: String, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    SettingsGroup(title, action = action) {
        Column(
            Modifier.fillMaxWidth().padding(BeaconUiMetrics.CardInset),
            verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
            content = content,
        )
    }
}

@Composable
fun SettingsPreferences(title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsGroup(title) {
        Column(
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = BeaconUiMetrics.CardInset,
                    vertical = BeaconUiMetrics.PreferenceInset,
                ),
            content = content,
        )
    }
}

@Composable
fun SettingsPreferenceActions(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(
                top = BeaconUiMetrics.SectionGap,
                bottom = BeaconUiMetrics.CardInset - BeaconUiMetrics.PreferenceInset,
            ),
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SectionGap),
        content = content,
    )
}

/**
 * The one standalone list card, for rows that are their own list item rather than a row inside a
 * settings group. Admin lists and message rows both needed the same shell, and had drifted into two
 * copies of the same radius/inset/rhythm; both now come from here.
 */
@Composable
internal fun BeaconListCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    BeaconCard(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(BeaconUiMetrics.CardRadius),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        content = { BeaconListCardColumn(content = content) },
    )
}

/** The body of a list card: one inset and one rhythm, even when the card needs its own modifiers. */
@Composable
internal fun BeaconListCardColumn(
    modifier: Modifier = Modifier,
    verticalInset: Dp = BeaconUiMetrics.SectionGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.padding(
            horizontal = BeaconUiMetrics.CardInset,
            vertical = verticalInset,
        ),
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4),
        content = content,
    )
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

/**
 * An inline failure or warning that belongs to the page rather than to one field.
 *
 * These used to be coloured text appended straight to a settings list, which left them outside every
 * card and pushed the section below them down by the height of the sentence. A notice is its own
 * small surface, so the page keeps its rhythm, and a failure that can be retried offers the action
 * instead of asking the reader to find it elsewhere.
 */
@Composable
fun SettingsNotice(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = BeaconIcons.Warning,
    tone: Color = MaterialTheme.colorScheme.error,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(BeaconUiMetrics.CardRadius)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                tone.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
            )
            .border(1.dp, tone.copy(alpha = 0.24f), shape)
            .padding(
                horizontal = BeaconUiMetrics.CardInset,
                vertical = BeaconUiMetrics.SectionGap,
            ),
        horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(BeaconUiMetrics.IconMedium), tint = tone)
        BeaconText(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null)
            BeaconTextButton(onClick = onAction) { BeaconText(actionLabel, color = tone) }
    }
}

/**
 * A primary action together with the reason it cannot be pressed yet.
 *
 * A greyed-out button on its own makes the reader hunt for the field that is missing. The reason sits
 * directly under the button, where the eye already is, and disappears as soon as the action becomes
 * available — no separate validation pass, no dialog.
 */
@Composable
fun SettingsPrimaryAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disabledReason: String? = null,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
    ) {
        SettingsActions {
            BeaconButton(onClick = onClick, enabled = enabled) { BeaconText(label) }
        }
        if (!enabled && disabledReason != null)
            BeaconText(
                disabledReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/** The section label above a card; the one place that decides how groups are announced. */
@Composable
fun SettingsLabel(text: String, modifier: Modifier = Modifier) {
    if (text.isNotBlank())
        BeaconText(
            text,
            modifier.padding(horizontal = BeaconUiMetrics.CardInset).semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
}

@Composable
fun SettingsGroup(title: String, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.GroupTitleGap)) {
        if (action == null) SettingsLabel(title)
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingsLabel(title, Modifier.weight(1f))
            action()
        }
        BeaconCard(
            Modifier.fillMaxWidth().animateContentSize(animationSpec = MotionResize),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
            content = content,
        )
    }
}

/**
 * Every settings page scrolls through [BeaconSettingsList], so one tag finds any of them. Tests
 * scroll by tag and then assert on visible text, which keeps them from depending on a page's
 * vertical layout.
 */
const val SETTINGS_LIST_TAG = "settings-list"

/**
 * The scroll container every settings surface uses: item spacing and keyboard avoidance are fixed
 * here so no page can drift away from the others.
 */
@Composable
fun BeaconSettingsList(
    state: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listTag: String = SETTINGS_LIST_TAG,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier.imePadding().testTag(listTag),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.SettingsGroupGap),
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
    enabled: Boolean = true,
    expanded: Boolean? = null,
    selection: Boolean = false,
    command: Boolean = false,
    danger: Boolean = false,
    external: Boolean = false,
) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth()
                .alpha(if (enabled) 1f else .38f)
                .heightIn(
                    min =
                        (if (command && subtitle.isBlank()) BeaconUiMetrics.ControlMinHeight
                        else BeaconUiMetrics.NavigationMinHeight) *
                            androidx.compose.ui.platform.LocalDensity.current.fontScale
                                .coerceAtLeast(1f)
                )
                .padding(
                    horizontal = BeaconUiMetrics.CardInset,
                    vertical = BeaconUiMetrics.NavigationVerticalInset,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap16),
        ) {
            BadgedIcon(icon, iconTint, badge)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap2)) {
                BeaconText(title, style = MaterialTheme.typography.bodyLarge,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (subtitle.isNotBlank())
                    BeaconText(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            if (!command) Icon(
                if (external) BeaconIcons.OpenWeb else if (selection) BeaconIcons.Select else BeaconIcons.Arrow,
                if (expanded == null) null
                else
                    stringResource(
                        if (expanded) R.string.settings_collapse else R.string.settings_expand
                    ),
                Modifier.then(
                    if (expanded == null) Modifier else Modifier.rotate(if (expanded) 270f else 90f)
                ),
            )
        }
    }
}

/** File pickers and other actions use the same native list geometry as navigation. */
@Composable
fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) =
    SettingsRow(
        title,
        subtitle,
        icon,
        MaterialTheme.colorScheme.onSurface,
        onClick,
        enabled = enabled,
    )

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
    modifier: Modifier = Modifier,
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
            modifier
                .fillMaxWidth()
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
        BeaconListCardColumn(verticalInset = BeaconUiMetrics.MessageCardVerticalInset) {
            // Top-aligned, and the trailing controls are pinned to the height of one title line:
            // the action belongs to the first line of the title, so it must not travel down when
            // the title wraps to two.
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap6),
            ) {
                if (selectionMode)
                    BeaconCheckbox(
                        selected,
                        onSelect,
                        modifier = Modifier.align(Alignment.CenterVertically),
                        enabled = !busy,
                        contentDescription = message.title,
                    )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap2),
                ) {
                    BeaconText(
                        message.title.ifBlank { stringResource(R.string.msg_untitled_message) },
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = BeaconListText.TitleSize,
                                lineHeight = BeaconListText.TitleLineHeight,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!message.isRead)
                    FirstLineSlot(BeaconListText.TitleLineHeight) {
                        Box(
                            Modifier.size(BeaconUiMetrics.UnreadDotSize)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    androidx.compose.foundation.shape.CircleShape,
                                )
                                .semantics { contentDescription = unreadLabel }
                        )
                    }
                if (!selectionMode)
                    FirstLineSlot(BeaconListText.TitleLineHeight) {
                        Box {
                            BeaconIconButton(
                                enabled = !busy,
                                modifier = Modifier.size(BeaconUiMetrics.MessageCardActionSize),
                                onClick = { menu = true },
                            ) {
                                Icon(
                                    BeaconIcons.More,
                                    stringResource(R.string.ui_message_more_action),
                                    Modifier.size(BeaconUiMetrics.MessageCardActionIconSize),
                                )
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
            }
            if (message.subtitle.isNotBlank())
                BeaconText(
                    message.subtitle,
                    style =
                        MaterialTheme.typography.titleSmall.copy(
                            fontSize = BeaconListText.SubtitleSize,
                            lineHeight = BeaconListText.SubtitleLineHeight,
                        ),
                    color =
                        MaterialTheme.colorScheme.onSurface.copy(
                            alpha = BeaconListText.SubtitleAlpha
                        ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            BeaconText(
                remember(message.body) {
                    io.github.beacon.app.domain.policy.MessageMarkdown.summary(message.body)
                },
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = BeaconListText.PreviewSize,
                        lineHeight = BeaconListText.PreviewLineHeight,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = BeaconListText.PreviewLines,
                overflow = TextOverflow.Ellipsis,
            )
            MessageCardFooter(
                message,
                busy,
                selectionMode,
                onFilterApplication,
                onFilterGroup,
            )
        }
    }
}

/**
 * Metadata chips; compact mode is reserved for the list footer.
 *
 * [toneSlot] selects which of the tinted hues this label draws from: the application and the group
 * are seeded with different slots so the two chips on one card are told apart even when the names
 * collide, while the same application keeps its colour on every message. `-1` (the default) keeps
 * the neutral chip used for controls that are not metadata (the render/source toggle).
 */
@Composable
internal fun MessageFilterTag(
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
    outlined: Boolean = false,
    toneSlot: Int = -1,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .semantics { if (compact) contentDescription = label }
            .clip(RoundedCornerShape(BeaconUiMetrics.BadgeRadius))
            .background(
                if (toneSlot < 0)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                        .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
                else beaconTagContainer(label, toneSlot)
            )
            .then(
                if (!outlined) Modifier
                else Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    RoundedCornerShape(BeaconUiMetrics.BadgeRadius),
                )
            )
            .then(
                if (onClick == null) Modifier
                else
                    Modifier.clickable(
                        enabled = enabled,
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClick = onClick,
                    )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = BeaconUiMetrics.Gap8, vertical = BeaconUiMetrics.BadgeVerticalInset),
            horizontalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        BeaconText(
            label,
            Modifier.weight(1f, fill = false),
            style =
                if (compact)
                    MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp)
                else MaterialTheme.typography.labelSmall,
            color = beaconTagContent(toneSlot),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        }
    }
}
