package io.github.beacon.app

import android.content.ComponentCallbacks2
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import io.github.beacon.app.ui.BeaconViewModel
import io.github.beacon.app.ui.components.SettingsScreen
import org.junit.Assert.*
import org.junit.Test

class BackgroundUiMemoryTest {
    @Test
    fun stoppedIdleWindowAutomaticallyReleasesItsComposition() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: BeaconViewModel
            scenario.onActivity { model = ViewModelProvider(it)[BeaconViewModel::class.java] }
            val readyBy = System.nanoTime() + 15_000_000_000L
            while (model.state.value.messageBusy || !model.state.value.connectionSummaryLoaded) {
                if (System.nanoTime() > readyBy) fail("UI did not become idle")
                Thread.sleep(50)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            var released = false
            val releaseBy = System.nanoTime() + 9_000_000_000L
            while (!released && System.nanoTime() < releaseBy) {
                scenario.onActivity { released = it.backgroundUiReleased }
                Thread.sleep(50)
            }
            assertTrue("Idle UI stayed composed until the cached freezer window", released)
            scenario.onActivity {
                assertFalse(contentComposition(it.window.decorView)!!.hasComposition)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertTrue(contentComposition(it.window.decorView)!!.hasComposition)
            }
        }
    }

    private fun contentComposition(view: View): ComposeView? {
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                contentComposition(view.getChildAt(index))?.let {
                    return it
                }
            }
        }
        return view as? ComposeView
    }

    @Test
    fun idleUiCanBeReleasedAndRestoredOnReturn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                val ui = ViewModelProvider(activity)[BeaconViewModel::class.java].uiState
                ui.settingsScreen = SettingsScreen.HOME
                ui.search = "memory-state"
                assertTrue(activity.releaseIdleBackgroundUi())
                assertTrue(activity.backgroundUiReleased)
                assertFalse(contentComposition(activity.window.decorView)!!.hasComposition)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertFalse(activity.backgroundUiReleased)
                assertTrue(contentComposition(activity.window.decorView)!!.hasComposition)
                assertEquals(
                    "memory-state",
                    ViewModelProvider(activity)[BeaconViewModel::class.java].uiState.search,
                )
                assertFalse(activity.releaseIdleBackgroundUi())
            }
        }
    }

    @Test
    fun editingAndDocumentPickerPagesStayAliveInBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                val ui = ViewModelProvider(activity)[BeaconViewModel::class.java].uiState
                for (screen in listOf(SettingsScreen.CONNECTION, SettingsScreen.BACKUP)) {
                    ui.settingsScreen = screen
                    assertFalse(activity.releaseIdleBackgroundUi())
                    assertFalse(activity.backgroundUiReleased)
                }
                ui.settingsScreen = SettingsScreen.HOME
                ui.filtersOpen = true
                assertFalse(activity.releaseIdleBackgroundUi())
                ui.filtersOpen = false
                ui.supportPreview = "report"
                assertFalse(activity.releaseIdleBackgroundUi())
                ui.supportPreview = null
                ui.operationDetails = "result"
                assertFalse(activity.releaseIdleBackgroundUi())
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun readOnlyPagesReleaseOnTrimAndRestoreNavigation() {
        for (screen in
            listOf(SettingsScreen.ABOUT, SettingsScreen.GUIDE, SettingsScreen.BACKGROUND)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { activity ->
                    val ui = ViewModelProvider(activity)[BeaconViewModel::class.java].uiState
                    ui.page = 1
                    ui.settingsScreen = screen
                    activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                    assertTrue(activity.backgroundUiReleased)
                    assertFalse(contentComposition(activity.window.decorView)!!.hasComposition)
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    assertEquals(
                        screen,
                        ViewModelProvider(activity)[BeaconViewModel::class.java]
                            .uiState
                            .settingsScreen,
                    )
                    assertTrue(contentComposition(activity.window.decorView)!!.hasComposition)
                }
            }
        }
    }

    @Test
    fun fullDetailIsReleasedAndReloadedThroughRuntimeOnReturn() {
        val key = io.github.beacon.app.domain.model.MessageKey("background-memory", java.util.UUID.randomUUID().toString())
        io.github.beacon.app.data.local.RuntimeMessageStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        ).use { store ->
            store.save(io.github.beacon.app.domain.model.IncomingMessage(
                key.server, key.id, "Memory detail", "Unique detail body", receivedAtMs = System.currentTimeMillis()
            ))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: BeaconViewModel
            scenario.onActivity { model = ViewModelProvider(it)[BeaconViewModel::class.java] }
            fun await(label: String, predicate: () -> Boolean) {
                val end = System.nanoTime() + 15_000_000_000L
                while (!predicate()) {
                    if (System.nanoTime() > end) fail("Timed out: $label")
                    Thread.sleep(50)
                }
            }
            await("idle binding") {
                model.state.value.runtimeConnected &&
                    model.state.value.connectionSummaryLoaded &&
                    !model.state.value.messageBusy
            }
            scenario.onActivity { model.readMessages() }
            await("injected message") {
                !model.state.value.messageBusy && model.state.value.messages.any { it.id == key.id }
            }
            scenario.onActivity {
                model.openMessage(key)
            }
            await("full detail") {
                !model.state.value.messageBusy && model.state.value.selectedMessage != null
            }
            val detail = requireNotNull(model.state.value.selectedMessage)
            assertEquals(key.id, detail.id)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                model.uiState.settingsScreen = SettingsScreen.HOME
                assertTrue(activity.releaseIdleBackgroundUi())
                assertNull(model.state.value.selectedMessage)
                assertEquals(key, model.uiState.detailKey)
                assertEquals(key, model.uiState.pendingNotification)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("detail restored") {
                model.state.value.selectedMessage?.id == detail.id && !model.state.value.messageBusy
            }
            assertEquals(detail.body, model.state.value.selectedMessage?.body)
        }
    }
}
