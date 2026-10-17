package io.github.beacon.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.beacon.app.MainActivity
import io.github.beacon.app.R

/**
 * Launcher shortcuts for the two things the app is actually used for.
 *
 * They are published dynamically instead of from `res/xml/shortcuts.xml` because the static form
 * needs the package name inside the XML, and the debug build appends `.debug` — a hardcoded name
 * would point at the wrong package there. Everything here is derived from the running context.
 *
 * The intent carries a tab hint only, never a server id, message id or credential, so a shortcut
 * cannot reach a specific message. That path stays in `NotificationOpenActivity`, which is not
 * exported.
 */
object AppShortcuts {
    /** Extra read by [MainActivity] to pick the landing tab. */
    const val EXTRA_TAB = "beacon.shortcut.tab"
    const val TAB_MESSAGES = "messages"
    const val TAB_SETTINGS = "settings"

    private const val ID_MESSAGES = "shortcut-messages"
    private const val ID_SETTINGS = "shortcut-settings"

    /**
     * Republish both shortcuts. Launchers may reject the call (rate limits, disabled shortcuts,
     * unsupported hardware), so failures are swallowed: a missing shortcut must never affect
     * startup.
     */
    fun publish(context: Context) {
        val icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val shortcuts =
            listOf(
                shortcut(
                    context,
                    ID_MESSAGES,
                    TAB_MESSAGES,
                    R.string.shortcut_messages_short,
                    R.string.shortcut_messages_long,
                    icon,
                    rank = 0,
                ),
                shortcut(
                    context,
                    ID_SETTINGS,
                    TAB_SETTINGS,
                    R.string.shortcut_settings_short,
                    R.string.shortcut_settings_long,
                    icon,
                    rank = 1,
                ),
            )
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (_: RuntimeException) {
            // Some launchers throw on unsupported devices; the app works without shortcuts.
        }
    }

    /**
     * Landing tab requested by [intent], or null when it came from somewhere else.
     *
     * Also reports the activation: launchers rank shortcuts by recorded usage, and without a report
     * a shortcut can be demoted or dropped from the long-press menu. Reporting never fails the
     * navigation, so it is best-effort.
     */
    fun requestedTab(context: Context, intent: Intent?): Int? {
        val tab = intent?.getStringExtra(EXTRA_TAB) ?: return null
        val index = requestedTab(tab) ?: return null
        try {
            ShortcutManagerCompat.reportShortcutUsed(context, shortcutId(tab))
        } catch (_: RuntimeException) {
            // Same contract as publishing: the shortcut is a convenience, never a dependency.
        }
        return index
    }

    private fun requestedTab(tab: String): Int? =
        when (tab) {
            TAB_MESSAGES -> 0
            TAB_SETTINGS -> 1
            else -> null
        }

    private fun shortcutId(tab: String): String =
        if (tab == TAB_SETTINGS) ID_SETTINGS else ID_MESSAGES

    private fun shortcut(
        context: Context,
        id: String,
        tab: String,
        shortLabel: Int,
        longLabel: Int,
        icon: IconCompat,
        rank: Int,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(context.getString(shortLabel))
            .setLongLabel(context.getString(longLabel))
            .setIcon(icon)
            .setRank(rank)
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .putExtra(EXTRA_TAB, tab)
            )
            .build()
}
