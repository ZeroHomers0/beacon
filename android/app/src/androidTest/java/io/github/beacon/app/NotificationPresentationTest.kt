package io.github.beacon.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.domain.model.NotificationChannelKind
import io.github.beacon.app.domain.receive.NotificationBatch
import io.github.beacon.app.runtime.notification.MessageNotifications
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NotificationPresentationTest {
    @Test
    fun singleContentAndBatchSummaryPreserveLockscreenPrivacy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val tag = "beacon.test.${java.util.UUID.randomUUID()}"
        fun awaitNotification(title: String): Notification {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val notification =
                    manager.activeNotifications.firstOrNull { it.tag == tag }?.notification
                if (notification?.extras?.getString(Notification.EXTRA_TITLE) == title)
                    return notification
                Thread.sleep(20)
            }
            error("System did not publish the requested notification")
        }
        try {
            assertTrue(
                MessageNotifications(context).post(NotificationBatch(tag, 1, true, "独立标题", "独立正文"))
            )
            val single = awaitNotification("独立标题")
            assertEquals("独立标题", single.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("独立正文", single.extras.getString(Notification.EXTRA_BIG_TEXT))
            assertFalse(single.publicVersion.extras.toString().contains("独立正文"))
            assertTrue(MessageNotifications(context).post(NotificationBatch(tag, 3, true)))
            val summary = awaitNotification("Beacon 消息摘要")
            assertEquals("Beacon 消息摘要", summary.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(summary.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("3 条"))
        } finally {
            manager.cancel(tag, 100)
        }
    }

    @Test
    fun prioritySelectsTheChannelTheUserAlreadyConfigured() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val tag = "beacon.test.${java.util.UUID.randomUUID()}"
        fun awaitNotification(title: String): Notification {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val notification =
                    manager.activeNotifications.firstOrNull { it.tag == tag }?.notification
                if (notification?.extras?.getString(Notification.EXTRA_TITLE) == title)
                    return notification
                Thread.sleep(20)
            }
            error("System did not publish the requested notification")
        }
        try {
            // The tier decides the channel, so a user who silenced one tier is not re-alerted here.
            assertTrue(
                MessageNotifications(context)
                    .post(NotificationBatch(tag, 1, true, "重要标题", "重要正文", null, 8))
            )
            assertEquals(
                NotificationChannelKind.IMPORTANT.id,
                awaitNotification("重要标题").channelId,
            )
            assertTrue(
                MessageNotifications(context)
                    .post(NotificationBatch(tag, 1, true, "普通标题", "普通正文", null, 5))
            )
            assertEquals(NotificationChannelKind.FRESH.id, awaitNotification("普通标题").channelId)
            assertTrue(
                MessageNotifications(context)
                    .post(NotificationBatch(tag, 1, false, "静默标题", "静默正文", null, 2))
            )
            assertEquals(
                NotificationChannelKind.HISTORY.id,
                awaitNotification("静默标题").channelId,
            )
        } finally {
            manager.cancel(tag, 100)
        }
    }
}
