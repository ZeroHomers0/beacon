package io.github.beacon.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Puts [value] on the clipboard under [label] and reports whether the system accepted it. Callers
 * that confirm the copy on screen must show that confirmation only when this returns true.
 *
 * [sensitive] keeps the clip out of clipboard history, out of the paste preview and out of
 * cross-device sync. The app's own credentials (server secret, push key, diagnostic exports) are
 * sensitive; a copied message is not, because the user pastes it elsewhere on purpose — often onto
 * another device — and the flag would silently break that.
 *
 * The extras key is written literally: the platform constant only exists from API 33, and older
 * releases simply ignore an unknown clipboard extra.
 */
fun copyToClipboard(
    context: Context,
    label: String,
    value: String,
    sensitive: Boolean = false,
): Boolean {
    val clip = ClipData.newPlainText(label, value)
    if (sensitive)
        clip.description.extras = PersistableBundle().apply { putBoolean(SENSITIVE_EXTRA, true) }
    return runCatching {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
        .isSuccess
}

private const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
