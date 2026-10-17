package io.github.beacon.app

import android.app.Notification
import android.app.NotificationManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.receive.NotificationBatch
import io.github.beacon.app.feature.settings.QuietHoursPanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.runtime.notification.MessageNotifications
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuietHoursSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        var saved = QuietHours()
        val form = io.github.beacon.app.feature.settings.QuietHoursFormState()
        val actions =
            object : RuntimeActions by repository {
                override fun quietHours(
                    value: QuietHours?,
                    onFailure: () -> Unit,
                    onComplete: (QuietHours) -> Unit,
                ) {
                    if (value != null) saved = value
                    onComplete(saved)
                }
            }
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    io.github.beacon.app.ui.theme.BeaconTheme(style) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            io.github.beacon.app.ui.components.BeaconActionIcon(io.github.beacon.app.ui.icons.BeaconIcons.Save, "保存", form.canSave) { form.save(actions) }
                            QuietHoursPanel(RuntimeState(runtimeConnected = true), actions, form)
                        }
                    }
                }
            }
            rule.waitForIdle()
            rule.runOnIdle { form.start = "23:15" }
            rule.onNodeWithContentDescription("保存").assertIsEnabled().performClick()
            assertEquals(1395, saved.startMinute)
            rule.runOnIdle { form.start = "08:00" }
            rule.onNodeWithContentDescription("保存").assertIsNotEnabled()
            rule.onNodeWithText("取消").assertDoesNotExist()
            assertEquals(1395, saved.startMinute)
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialSettingsValidateDraft() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixSettingsValidateDraft() = verify(BeaconUiStyle.MIUIX)

    @Test
    fun realRuntimePersistsPolicyAcrossBindingAndSilentNotificationHasNoChildAlert() {
        val repository = RuntimeRepository()
        rule.runOnUiThread { repository.start(rule.activity) }
        rule.waitUntil(15000) { repository.state.value.runtimeConnected }
        var original = QuietHours()
        val read = CountDownLatch(1)
        rule.runOnUiThread {
            repository.quietHours(null) {
                original = it
                read.countDown()
            }
        }
        assertTrue(read.await(10, TimeUnit.SECONDS))
        val changed = QuietHours(true, 1200, 360, 31, QuietHoursBehavior.SAVE_ONLY)
        val wrote = CountDownLatch(1)
        try {
            rule.runOnUiThread {
                repository.quietHours(changed) {
                    assertEquals(changed, it)
                    wrote.countDown()
                }
            }
            assertTrue(wrote.await(10, TimeUnit.SECONDS))
            rule.runOnUiThread {
                repository.stop()
                repository.start(rule.activity)
            }
            rule.waitUntil(15000) { repository.state.value.runtimeConnected }
            val reread = CountDownLatch(1)
            rule.runOnUiThread {
                repository.quietHours(null) {
                    assertEquals(changed, it)
                    reread.countDown()
                }
            }
            assertTrue(reread.await(10, TimeUnit.SECONDS))
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            rule.activity.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
        )
        val tag = "beacon.quiet.test.${java.util.UUID.randomUUID()}"
            val manager = rule.activity.getSystemService(NotificationManager::class.java)
            try {
                assertTrue(
                    MessageNotifications(rule.activity)
                        .post(NotificationBatch(tag, 1, false, "静音标题", "静音正文"))
                )
                rule.waitUntil(5000) { manager.activeNotifications.any { it.tag == tag } }
                val notification = manager.activeNotifications.first { it.tag == tag }.notification
                assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
                assertEquals("beacon.silent", notification.group)
                assertEquals(0, notification.flags and Notification.FLAG_GROUP_SUMMARY)
                assertEquals("beacon_history_v1", notification.channelId)
            } finally {
                manager.cancel(tag, 100)
            }
        } finally {
            val restored = CountDownLatch(1)
            rule.runOnUiThread { repository.quietHours(original) { restored.countDown() } }
            restored.await(10, TimeUnit.SECONDS)
            rule.runOnUiThread { repository.close() }
        }
    }
}
