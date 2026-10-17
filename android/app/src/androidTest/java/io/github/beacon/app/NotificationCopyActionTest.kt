package io.github.beacon.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.receive.NotificationBatch
import io.github.beacon.app.runtime.notification.MessageNotifications
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * The copy action is the one card operation whose result lives outside the app, so it needs a
 * focused window to read the clipboard back. Everything else about the card is asserted in
 * NotificationActionTest, which runs without an activity.
 */
class NotificationCopyActionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun awaitNotification(manager: NotificationManager, tag: String): Notification {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val notification =
                manager.activeNotifications.firstOrNull { it.tag == tag }?.notification
            if (notification != null) return notification
            Thread.sleep(20)
        }
        error("System did not publish the requested notification revision")
    }

    @Test
    fun copyActionWritesTheBodyAndLeavesTheNoticeUntouched() {
        val context = rule.activity
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val key = MessageKey(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val tag = "beacon.batch.${System.currentTimeMillis()}"
        val body = "copy action body ${UUID.randomUUID()}"
        RuntimeMessageStore(context).use { store ->
            store.save(IncomingMessage(key.server, key.id, "copy title", body))
            try {
                rule.runOnUiThread { clipboard.clearPrimaryClip() }
                assertTrue(
                    MessageNotifications(context)
                        .post(NotificationBatch(tag, 1, false, "copy title", body, key))
                )
                val notice = awaitNotification(manager, tag)
                val copy =
                    notice.actions.firstOrNull {
                        it.title.toString() == context.getString(R.string.action_copy_body)
                    }
                assertNotNull(copy)
                copy!!.actionIntent.send()
                var copied: String? = null
                try {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                    while (copied != body && android.os.SystemClock.elapsedRealtime() < deadline) {
                        rule.runOnUiThread {
                            copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                        }
                        if (copied != body) Thread.sleep(30)
                    }
                    assertEquals(body, copied)
                } finally {
                    // Only a focused window may read the clipboard, so the rule must not be
                    // released with the test's own clip still pending.
                    clipboard.clearPrimaryClip()
                }
                // Copying is not reading: the notice stays and the message stays unread.
                assertTrue(manager.activeNotifications.any { it.tag == tag })
                assertFalse(store.detail(key.server, key.id)!!.isRead)
                assertTrue(store.pendingNotifications().any { it.key == key })
            } finally {
                manager.cancel(tag, 100)
                store.remove(key.server, key.id)
            }
        }
    }
}
