package io.github.beacon.app.runtime.notification

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.beacon.app.R

/**
 * Non-exported, runtime-owned action. The text travels in the intent because the notice is the only
 * place that still holds it once the reminder intent has been consumed.
 *
 * Copying deliberately leaves the notice in place: whether the message counts as read stays the
 * user's next decision instead of a side effect of copying.
 */
class NotificationCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != COPY_BODY) return
        val text = intent.getStringExtra(TEXT)?.takeIf { it.isNotBlank() } ?: return
        val copied =
            runCatching {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(
                            ClipData.newPlainText(context.getString(R.string.ui_widget_body_label), text)
                        )
                }
                .isSuccess
        // Only a standard text toast renders for a background app; the looper of this worker
        // process is not prepared, so the toast must be posted to the main thread.
        Handler(Looper.getMainLooper())
            .post {
                Toast.makeText(
                        context,
                        if (copied) R.string.settings_copied_hint else R.string.notification_copy_failed,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
    }

    companion object {
        internal const val COPY_BODY = "io.github.beacon.app.notification.COPY_BODY"
        internal const val TEXT = "notification_text"
    }
}
