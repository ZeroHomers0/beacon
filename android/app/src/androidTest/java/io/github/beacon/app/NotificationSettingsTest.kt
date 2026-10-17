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
import io.github.beacon.app.feature.settings.NotificationFormState
import io.github.beacon.app.feature.settings.NotificationSettingsPanel
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.runtime.notification.MessageNotifications
import io.github.beacon.app.ui.theme.BeaconTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun verify(style: BeaconUiStyle) {
        val repository = RuntimeRepository()
        var saved = NotificationSettings()
        var failWrite = false
        val actions =
            object : RuntimeActions by repository {
                override fun notificationSettings(
                    value: NotificationSettings?,
                    onFailure: () -> Unit,
                    onComplete: (NotificationSettings) -> Unit,
                ) {
                    if (value != null && failWrite) {
                        onFailure()
                        return
                    }
                    if (value != null) saved = value
                    onComplete(saved)
                }
            }
        // The panel renders and edits the draft; committing it is the caller's job, so the form
        // starts loaded here instead of waiting for the runtime to hand settings over.
        val form = NotificationFormState().apply { reset(NotificationSettings()) }
        form.loaded = true
        try {
            rule.runOnUiThread {
                rule.activity.setContent {
                    BeaconTheme(style) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            NotificationSettingsPanel(
                                RuntimeState(runtimeConnected = true),
                                actions,
                                form,
                                onRetrySave = {},
                            )
                        }
                    }
                }
            }
            rule.onNodeWithText("提醒有效时间").performClick()
            rule.onNodeWithText("15 分钟").performClick()
            assertEquals(15, form.draft.freshMinutes)
            rule.onNodeWithText("过时消息处理").performClick()
            rule.onNodeWithText("仅保存").performClick()
            assertEquals(OutdatedReminder.SAVE_ONLY, form.draft.outdatedReminder)
            rule.runOnUiThread { form.save(actions) }
            rule.waitForIdle()
            assertEquals(15, form.saved.freshMinutes)
            assertEquals(OutdatedReminder.SAVE_ONLY, form.saved.outdatedReminder)
            assertEquals(15, saved.freshMinutes)
            failWrite = true
            rule.onNodeWithText("提醒有效时间").performScrollTo().performClick()
            rule.onNodeWithText("1 分钟").performClick()
            rule.runOnUiThread { form.save(actions) }
            rule.waitForIdle()
            // A rejected write keeps both the stored value and the user's draft on screen.
            assertTrue(form.failed)
            assertEquals(15, saved.freshMinutes)
            assertEquals(15, form.saved.freshMinutes)
            assertEquals(1, form.draft.freshMinutes)
            rule.onNodeWithText("提醒有效时间").assertIsEnabled()
        } finally {
            rule.runOnUiThread { repository.close() }
        }
    }

    @Test fun materialSettingsPersistAndFailedSaveRetainsValues() = verify(BeaconUiStyle.MATERIAL3)

    @Test fun miuixSettingsPersistAndFailedSaveRetainsValues() = verify(BeaconUiStyle.MIUIX)

    @Test
    fun realRuntimePersistsAndNotificationContentStaysPrivate() {
        val repository = RuntimeRepository()
        rule.runOnUiThread { repository.start(rule.activity) }
        rule.waitUntil(15000) { repository.state.value.runtimeConnected }
        var original = NotificationSettings()
        val read = CountDownLatch(1)
        rule.runOnUiThread {
            repository.notificationSettings {
                original = it
                read.countDown()
            }
        }
        assertTrue(read.await(10, TimeUnit.SECONDS))
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            rule.activity.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
        )
        val changed = NotificationSettings(15, OutdatedReminder.SAVE_ONLY)
        try {
            val wrote = CountDownLatch(1)
            rule.runOnUiThread {
                repository.notificationSettings(changed) {
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
                repository.notificationSettings {
                    assertEquals(changed, it)
                    reread.countDown()
                }
            }
            assertTrue(reread.await(10, TimeUnit.SECONDS))
            val manager = rule.activity.getSystemService(NotificationManager::class.java)
            val tag = "beacon.privacy.test.${java.util.UUID.randomUUID()}"
            try {
                assertTrue(
                    MessageNotifications(rule.activity)
                        .post(NotificationBatch(tag, 1, true, "私密标题", "私密正文"))
                )
                rule.waitUntil(5000) { manager.activeNotifications.any { it.tag == tag } }
                val notification = manager.activeNotifications.first { it.tag == tag }.notification
                // Content is never promoted to the lock screen: there is no per-setting opt-in.
                assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
                assertEquals(
                    rule.activity.getString(R.string.notification_public_body),
                    notification.publicVersion.extras.getString(Notification.EXTRA_TEXT),
                )
                assertEquals(
                    "私密正文",
                    notification.extras.getString(Notification.EXTRA_TEXT),
                )
            } finally {
                manager.cancel(tag, 100)
            }
        } finally {
            val restored = CountDownLatch(1)
            rule.runOnUiThread {
                repository.notificationSettings(original) { restored.countDown() }
            }
            restored.await(10, TimeUnit.SECONDS)
            rule.runOnUiThread { repository.close() }
        }
    }
}
