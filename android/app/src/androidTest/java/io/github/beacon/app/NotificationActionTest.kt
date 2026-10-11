package io.github.beacon.app

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.receive.NotificationBatch
import io.github.beacon.app.runtime.notification.MessageNotifications
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NotificationActionTest {
    private fun awaitNotification(
        manager: NotificationManager,
        tag: String,
        title: String? = null,
    ): android.app.Notification {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val notification =
                manager.activeNotifications.firstOrNull { it.tag == tag }?.notification
            if (
                notification != null &&
                    (title == null ||
                        notification.extras.getString(android.app.Notification.EXTRA_TITLE) ==
                            title)
            )
                return notification
            Thread.sleep(20)
        }
        error("System did not publish the requested notification revision")
    }

    @Test
    fun repeatedNotificationsHaveIndependentReadActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val key = MessageKey(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val first = "beacon.batch.${System.currentTimeMillis()}.first"
        val second = "beacon.batch.${System.currentTimeMillis()}.second"
        RuntimeMessageStore(context).use { store ->
            store.save(IncomingMessage(key.server, key.id, "repeat title", "repeat body"))
            try {
                assertTrue(
                    MessageNotifications(context)
                        .post(
                            NotificationBatch(first, 1, false, "repeat title", "repeat body", key)
                        )
                )
                val old = awaitNotification(manager, first).actions.single().actionIntent
                assertTrue(
                    MessageNotifications(context)
                        .post(
                            NotificationBatch(second, 1, false, "repeat title", "repeat body", key)
                        )
                )
                val newest = awaitNotification(manager, second).actions.single().actionIntent
                assertNotEquals(old, newest)
                old.send()
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                while (
                    manager.activeNotifications.any { it.tag == first } &&
                        android.os.SystemClock.elapsedRealtime() < deadline
                ) Thread.sleep(25)
                assertFalse(manager.activeNotifications.any { it.tag == first })
                assertTrue(manager.activeNotifications.any { it.tag == second })
            } finally {
                manager.cancel(first, 100)
                manager.cancel(second, 100)
                store.remove(key.server, key.id)
            }
        }
    }

    @Test
    fun notificationTapOpensExactDetailIncludingSecondTapInExistingActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val first = MessageKey(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val second = first.copy(id = UUID.randomUUID().toString())
        val tag = "beacon.batch.${System.currentTimeMillis()}"
        val monitor =
            instrumentation.addMonitor(NotificationOpenActivity::class.java.name, null, false)
        var opened: android.app.Activity? = null
        RuntimeMessageStore(context).use { store ->
            store.save(IncomingMessage(first.server, first.id, "first notification", "first body"))
            store.save(
                IncomingMessage(second.server, second.id, "second notification", "second body")
            )
            fun tap(key: MessageKey) {
                assertTrue(
                    MessageNotifications(context)
                        .post(NotificationBatch(tag, 1, false, key.id, "tap body", key))
                )
                awaitNotification(manager, tag, key.id).contentIntent.send()
                val deadline = android.os.SystemClock.elapsedRealtime() + 15000
                while (
                    store.detail(key.server, key.id)?.isRead != true &&
                        android.os.SystemClock.elapsedRealtime() < deadline
                ) Thread.sleep(30)
                assertTrue(
                    "Notification destination must load its exact detail",
                    store.detail(key.server, key.id)!!.isRead,
                )
            }
            try {
                tap(first)
                opened = monitor.waitForActivityWithTimeout(5000)
                assertTrue(opened is NotificationOpenActivity)
                assertFalse(store.detail(second.server, second.id)!!.isRead)
                tap(second)
            } finally {
                instrumentation.runOnMainSync { opened?.finish() }
                instrumentation.removeMonitor(monitor)
                manager.cancel(tag, 100)
                store.remove(first.server, first.id)
                store.remove(second.server, second.id)
            }
        }
    }

    @Test
    fun systemReadActionPersistsReadAndKeepsOtherMessages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val key = MessageKey(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val other = key.copy(id = UUID.randomUUID().toString())
        val tag = "beacon.batch.${System.currentTimeMillis()}"
        RuntimeMessageStore(context).use { store ->
            store.save(IncomingMessage(key.server, key.id, "action title", "action body"))
            store.save(IncomingMessage(other.server, other.id, "other title", "other body"))
            try {
                assertTrue(
                    MessageNotifications(context)
                        .post(NotificationBatch(tag, 1, false, "action title", "action body", key))
                )
                val notice = awaitNotification(manager, tag)
                assertEquals(1, notice.actions.size)
                assertEquals("标为已读", notice.actions.single().title.toString())
                notice.actions.single().actionIntent.send()
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                while (
                    store.detail(key.server, key.id)?.isRead != true &&
                        android.os.SystemClock.elapsedRealtime() < deadline
                ) Thread.sleep(30)
                assertTrue(store.detail(key.server, key.id)!!.isRead)
                assertTrue(store.pendingIds(key.server).contains(key.id))
                assertFalse(store.detail(other.server, other.id)!!.isRead)
                assertFalse(store.pendingNotifications().any { it.key == key })
                assertTrue(store.pendingNotifications().any { it.key == other })
            } finally {
                manager.cancel(tag, 100)
                store.remove(key.server, key.id)
                store.remove(other.server, other.id)
            }
        }
    }

    @Test
    fun notificationReadTransactionRollsBackIfReminderRemovalFails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = MessageKey(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        RuntimeMessageStore(context).use { store ->
            store.save(IncomingMessage(key.server, key.id, "rollback", "body"))
            val db = store.writableDatabase
            db.execSQL(
                "CREATE TEMP TRIGGER fail_notification_delete BEFORE DELETE ON notification_intents BEGIN SELECT RAISE(ABORT, 'test failure'); END"
            )
            try {
                assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    store.readFromNotification(key)
                }
                assertFalse(store.detail(key.server, key.id)!!.isRead)
                assertTrue(store.pendingNotifications().any { it.key == key })
            } finally {
                db.execSQL("DROP TRIGGER fail_notification_delete")
                store.remove(key.server, key.id)
            }
        }
    }
}
