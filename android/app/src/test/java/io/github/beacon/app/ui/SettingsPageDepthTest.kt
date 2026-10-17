package io.github.beacon.app.ui

import io.github.beacon.app.ui.components.SettingsScreen
import io.github.beacon.app.ui.components.settingsPageDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageDepthTest {
    @Test
    fun administrationReturnLayersStayAboveTheirParents() {
        val home = settingsPageDepth(SettingsScreen.HOME)
        val admin = settingsPageDepth(SettingsScreen.SERVER_ADMIN)
        assertEquals(0, home)
        assertTrue(admin > home)
        listOf(
            SettingsScreen.SERVER_DEVICES,
            SettingsScreen.SERVER_APPLICATIONS,
            SettingsScreen.SERVER_TEST,
        ).forEach { screen -> assertTrue(settingsPageDepth(screen) > admin) }
    }

    @Test
    fun quietHoursStayAboveNotificationRules() {
        assertTrue(
            settingsPageDepth(SettingsScreen.QUIET_HOURS) >
                settingsPageDepth(SettingsScreen.NOTIFICATION_RULES)
        )
    }
}
