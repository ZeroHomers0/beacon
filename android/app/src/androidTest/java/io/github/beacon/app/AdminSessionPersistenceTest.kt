package io.github.beacon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.TransportSecretStore
import io.github.beacon.app.data.remote.AdminRequestException
import io.github.beacon.app.data.remote.ServerAdministration
import io.github.beacon.app.domain.model.AppRelease
import io.github.beacon.app.feature.settings.ServerAdminViewModel
import io.github.beacon.app.feature.settings.UpdateCheckState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdminSessionPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext

    private val endpoint = "https://example.com"
    private val token = "a".repeat(64)

    private fun store() = TransportSecretStore(context, "admin_secret", "beacon_admin_v1")

    private fun client(unauthorized: Boolean = false) =
        object : ServerAdministration {
            override val endpoint = this@AdminSessionPersistenceTest.endpoint

            override fun overview(): JSONObject {
                if (unauthorized) throw AdminRequestException(401)
                return JSONObject(
                    """{"server_id":"server","devices":[],"applications":[],"storage":{"messages":0,"pending":0,"database_allocated_bytes":0}}"""
                )
            }

            override fun binding(name: String) = JSONObject()

            override fun revokeDevice(id: String) = JSONObject()

            override fun test(id: String, title: String, body: String, priority: Int) = JSONObject()

            override fun application(id: String, name: String, priority: Int) = JSONObject()

            override fun revokeApplication(id: String) = JSONObject()

            override fun deleteApplication(id: String) = JSONObject()
        }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue(condition())
    }

    @Test
    fun loginRestoresAcrossViewModelsAndLogoutRemovesEncryptedCredentials() {
        store().clear()
        val first = ServerAdminViewModel { _, _ -> client() }
        instrumentation.runOnMainSync {
            first.restoreSession(context)
            first.login(endpoint, token)
        }
        waitUntil {
            first.state.value.loggedIn && !first.state.value.busy && store().isConfigured()
        }
        assertEquals(token, store().load().token)
        val prefs = context.getSharedPreferences("admin_secret", 0)
        assertFalse(prefs.all.values.joinToString().contains(token))
        val restored = ServerAdminViewModel { url, savedToken ->
            assertEquals(endpoint, url)
            assertEquals(token, savedToken)
            client()
        }
        instrumentation.runOnMainSync { restored.restoreSession(context) }
        waitUntil { restored.state.value.loggedIn && !restored.state.value.busy }
        instrumentation.runOnMainSync { restored.logout() }
        assertFalse(store().isConfigured())
        assertFalse(restored.state.value.loggedIn)
    }

    @Test
    fun unauthorizedRestoreClearsCredentials() {
        store().save(io.github.beacon.app.domain.model.TransportSettings(endpoint, token))
        val model = ServerAdminViewModel { _, _ -> client(true) }
        instrumentation.runOnMainSync { model.restoreSession(context) }
        waitUntil { model.state.value.feedback == R.string.admin_unauthorized }
        assertFalse(model.state.value.loggedIn)
        assertFalse(store().isConfigured())
    }

    @Test
    fun updateAcknowledgementSurvivesNewState() = runBlocking {
        val prefs = context.getSharedPreferences("test_update_notice", 0)
        prefs.edit().clear().commit()
        val release = AppRelease("1.0.4", "", "https://example.com/app.apk", 10)
        val state = UpdateCheckState()
        state.attachPreferences(prefs)
        state.check(false) { Result.success(release) }
        state.acknowledge()
        val restored = UpdateCheckState()
        restored.attachPreferences(prefs)
        restored.check(false) { Result.success(release) }
        assertFalse(restored.hasUnreadUpdate)
        prefs.edit().clear().commit()
        Unit
    }
}
