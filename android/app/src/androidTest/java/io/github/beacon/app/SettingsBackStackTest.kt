package io.github.beacon.app

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.ui.BeaconUiState
import io.github.beacon.app.ui.components.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsBackStackTest {
    @Test fun quietHoursDraftLivesAcrossChildNavigationButReloadsAfterLeavingEditor() {
        val ui = BeaconUiState(SavedStateHandle(), UiStyleStore(InstrumentationRegistry.getInstrumentation().targetContext))
        ui.navigateSettings(SettingsScreen.NOTIFICATION_RULES)
        ui.quietHoursForm.loaded = true
        ui.quietHoursForm.start = "23:15"
        ui.navigateSettings(SettingsScreen.QUIET_HOURS)
        ui.backSettings()
        assertEquals(true, ui.quietHoursForm.loaded)
        assertEquals("23:15", ui.quietHoursForm.start)
        ui.backSettings()
        assertEquals(false, ui.quietHoursForm.loaded)
        ui.navigateSettings(SettingsScreen.APPEARANCE)
        assertEquals(SettingsScreen.APPEARANCE, ui.settingsScreen)
    }

    @Test fun nestedSettingsReturnToTheirSourceAndSurviveStateRestoration() {
        val handle = SavedStateHandle()
        val style = UiStyleStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val ui = BeaconUiState(handle, style)
        ui.page = 1
        ui.navigateSettings(SettingsScreen.SERVER_ADMIN)
        ui.navigateSettings(SettingsScreen.CONNECTION)
        ui.navigateSettings(SettingsScreen.NOTIFICATIONS)
        val restored = BeaconUiState(handle, style)
        restored.finishNavigation(1)
        assertEquals(SettingsScreen.CONNECTION, restored.settingsScreen)
        restored.finishNavigation(1)
        assertEquals(SettingsScreen.SERVER_ADMIN, restored.settingsScreen)
        restored.finishNavigation(1)
        assertEquals(SettingsScreen.HOME, restored.settingsScreen)
        ui.navigateSettings(SettingsScreen.GUIDE)
        ui.navigateSettings(SettingsScreen.RECEPTION)
        ui.backSettings()
        assertEquals(SettingsScreen.GUIDE, ui.settingsScreen)
    }
}
