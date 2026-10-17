package io.github.beacon.app

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ui.BeaconViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarkAllReadTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun bulkUpdateCoversEveryPageAndServerWithoutChangingReceiptsOrNotices() {
        val base = rule.activity.applicationContext
        val prefix = "all-read-${java.util.UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = base.getDatabasePath(prefix + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = base.openOrCreateDatabase(prefix + name, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) = base.openOrCreateDatabase(prefix + name, mode, factory, handler)
        }
        try {
            RuntimeMessageStore(context).use { store ->
                repeat(221) { store.save(IncomingMessage("server-${it % 2}", "$it", "synthetic-$it", "body")) }
                store.mutate(MessageMutation(MessageAction.MARK_READ, listOf(MessageKey("server-0", "0"))))
                val receipts = (0..1).map { store.pendingIds("server-$it") }
                val notices = store.pendingNotifications()
                val total = store.page(MessageQuery()).total
                assertEquals(220L, store.markAllRead())
                assertEquals(total, store.page(MessageQuery()).total)
                assertEquals(0L, store.page(MessageQuery()).unread)
                assertEquals(0L, store.page(MessageQuery(readFilter = MessageReadFilter.UNREAD)).total)
                assertEquals(receipts, (0..1).map { store.pendingIds("server-$it") })
                assertEquals(notices, store.pendingNotifications())
                assertEquals(0L, store.markAllRead())
            }
        } finally { base.deleteDatabase(prefix + "beacon-messages.db") }
    }

    private fun toolbar(style: BeaconUiStyle) {
        val model = ViewModelProvider(rule.activity)[BeaconViewModel::class.java]
        val originalStyle = model.uiState.uiStyle
        val token = java.util.UUID.randomUUID().toString()
        val server = "all-read-$token"
        rule.waitUntil(20_000) { model.state.value.runtimeConnected && model.state.value.messagesLoaded && !model.state.value.messageBusy }
        RuntimeMessageStore(rule.activity).use { store ->
            repeat(121) { store.save(IncomingMessage("$server-${it % 2}", "$it", if (it < 3) "needle-$token" else "outside-$token", "body")) }
        }
        try {
            rule.runOnUiThread {
                model.uiState.uiStyle = style
                model.uiState.page = 0
                model.uiState.closeDetail(model)
                model.uiState.applyFilter(model, "needle-$token", MessageReadFilter.UNREAD)
            }
            rule.waitUntil(20_000) { !model.state.value.messageBusy && model.state.value.messageTotal == 3L }
            val button = rule.onNodeWithContentDescription("全部已读")
            button.assertIsDisplayed().assertIsEnabled()
            assertTrue(button.getUnclippedBoundsInRoot().top.value < 100)
            button.performClick()
            rule.waitUntil(20_000) { !model.state.value.messageBusy && model.state.value.messageTotal == 0L }
            rule.runOnIdle {
                assertEquals("needle-$token", model.state.value.messageQuery.search)
                assertEquals(MessageReadFilter.UNREAD, model.state.value.messageQuery.readFilter)
            }
            RuntimeMessageStore(rule.activity).use { store ->
                repeat(121) { assertTrue(store.detail("$server-${it % 2}", "$it")!!.isRead) }
                assertEquals(121L, store.page(MessageQuery(search = token)).total)
            }
            // An already-read filtered view still offers the global action safely.
            rule.runOnUiThread { model.uiState.applyFilter(model, "outside-$token", MessageReadFilter.READ) }
            rule.waitUntil(20_000) { !model.state.value.messageBusy && model.state.value.messageTotal == 118L }
            button.assertIsEnabled().performClick()
            rule.waitUntil(20_000) { !model.state.value.messageBusy && model.state.value.messageTotal == 118L }
        } finally {
            rule.runOnUiThread { model.uiState.uiStyle = originalStyle; model.uiState.applyFilter(model, "", MessageReadFilter.ALL) }
            rule.waitUntil(20_000) { !model.state.value.messageBusy }
        }
    }
    @Test fun materialToolbarMarksAllMessagesAcrossFilterAndPages() = toolbar(BeaconUiStyle.MATERIAL3)
    @Test fun miuixToolbarMarksAllMessagesAcrossFilterAndPages() = toolbar(BeaconUiStyle.MIUIX)
}
