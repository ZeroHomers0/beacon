package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.beacon.app.data.remote.ServerAdministration
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.feature.settings.ServerAdminPage
import io.github.beacon.app.feature.settings.ServerAdminViewModel
import io.github.beacon.app.ui.theme.BeaconTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

class ServerAdminPageTest {
    private val screen = androidx.compose.runtime.mutableStateOf(io.github.beacon.app.ui.components.SettingsScreen.SERVER_ADMIN)
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before @After fun clearFixtureSession() {
        io.github.beacon.app.data.local.TransportSecretStore(
            rule.activity, "admin_secret", "beacon_admin_v1",
        ).clear()
    }

    private fun loginValidation(style: BeaconUiStyle, fontScale: Float) {
        // Administrator outcomes are reported through the app-wide snackbar, not an inline panel.
        val feedback = java.util.concurrent.atomic.AtomicReference("")
        rule.runOnUiThread {
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale)
                ) {
                    BeaconTheme(style) {
                        ServerAdminPage(
                            "",
                            rememberLazyListState(),
                            Modifier,
                            PaddingValues(16.dp),
                            { _, _, _ -> },
                            { feedback.set(it) },
                            screen = screen.value, onNavigate = { screen.value = it },
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("登录管理").performScrollTo().assertIsNotEnabled()
        rule
            .onAllNodes(hasSetTextAction())[0]
            .performScrollTo()
            .performTextReplacement("http://example.invalid")
        rule.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("bad")
        rule.onNodeWithText("登录管理").performScrollTo().performClick()
        rule.waitUntil(5000) { feedback.get().isNotEmpty() }
        assertEquals(rule.activity.getString(R.string.admin_invalid_login), feedback.get())
        rule
            .onAllNodes(hasSetTextAction())[1]
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString(""),
                )
            )
        rule.onNodeWithText("登录管理").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun materialLoginDoesNotAcceptUnsafeCredentials() = loginValidation(BeaconUiStyle.MATERIAL3, 1f)

    @Test
    fun miuixLoginAtLargeFontDoesNotAcceptUnsafeCredentials() =
        loginValidation(BeaconUiStyle.MIUIX, 1.5f)

    private fun management(style: BeaconUiStyle) {
        val backend = FixtureAdmin()
        val model = ServerAdminViewModel { _, _ -> backend }
        rule.runOnUiThread {
            androidx.lifecycle
                .ViewModelProvider(
                    rule.activity,
                    object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(
                            modelClass: Class<T>
                        ): T = model as T
                    },
                )[ServerAdminViewModel::class.java]
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    BeaconTheme(style) {
                        ServerAdminPage(
                            "",
                            rememberLazyListState(),
                            Modifier,
                            PaddingValues(16.dp),
                            { _, _, _ -> },
                            {},
                            screen = screen.value, onNavigate = { screen.value = it },
                        )
                    }
                }
            }
        }
        rule
            .onAllNodes(hasSetTextAction())[0]
            .performScrollTo()
            .performTextReplacement("https://example.invalid")
        rule
            .onAllNodes(hasSetTextAction())[1]
            .performScrollTo()
            .performTextReplacement("a".repeat(64))
        rule.onNodeWithText("登录管理").performScrollTo().performClick()
        rule.waitUntil(5000) { model.state.value.loggedIn && !model.state.value.busy }
        rule.onNodeWithText("1.0.0").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("server-admin-list").performScrollToNode(hasText("设备") and hasClickAction())
        rule.onNode(hasText("设备") and hasClickAction()).performClick()
        rule.onNode(hasText("创建绑定码") and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithTag("server-admin-list").performScrollToNode(hasSetTextAction())
        rule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("new phone")
        rule.onAllNodes(hasSetTextAction())[0].performImeAction()
        rule.onNode(hasText("创建") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(5000) { model.state.value.secret != null }
        rule.onNodeWithText("b".repeat(32)).assertIsDisplayed()
        rule.onAllNodesWithText("一键绑定当前手机").onLast().assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule.runOnIdle { screen.value = io.github.beacon.app.ui.components.SettingsScreen.SERVER_ADMIN }
        rule.onNodeWithTag("server-admin-list").performScrollToNode(hasText("应用") and hasClickAction())
        rule.onNode(hasText("应用") and hasClickAction()).performClick()
        rule.onNode(hasText("创建发送应用") and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithTag("server-admin-list").performScrollToNode(hasText("目标设备"))
        rule.onNodeWithText("目标设备").performScrollTo().assertIsDisplayed()
        val labelBounds =
            rule.onNodeWithText("目标设备", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Device label must fit on one line at large font",
            labelBounds.height < 60 * rule.density.density,
        )
        rule.onNodeWithText("我的小米手机 · 日常消息接收设备 · phone-1").assertIsDisplayed()
        val screenshot = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io
            .File(rule.activity.getExternalFilesDir(null), "admin-layout-${style.name}.png")
            .outputStream()
            .use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        rule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("NAS")
        rule.onAllNodes(hasSetTextAction())[0].performImeAction()
        // Card heading and action share a title; choose the enabled button semantics.
        rule
            .onAllNodes(hasText("创建发送应用") and hasClickAction()).onLast()
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        rule.waitUntil(5000) { model.state.value.secret?.example?.isNotEmpty() == true }
        rule.onNodeWithText("c".repeat(64)).assertIsDisplayed()
        rule.onNodeWithText("复制 curl 推送示例").performClick()
        rule.onNodeWithText("已复制").assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule
            .onNodeWithTag("server-admin-list")
            .performScrollToNode(hasText("NAS") and !hasSetTextAction())
        rule.onNode(hasText("NAS") and !hasSetTextAction()).assertIsDisplayed()
        rule.onNodeWithText("复制推送地址").assertDoesNotExist()
        rule.onNodeWithContentDescription("更多操作").performScrollTo().performClick()
        rule.onNodeWithText("撤销发送凭证").performClick()
        rule
            .onNodeWithText(rule.activity.getString(R.string.admin_revoke_application_hint, "NAS"))
            .assertIsDisplayed()
        rule.onAllNodes(hasText("撤销发送凭证") and hasClickAction()).onLast().performClick()
        rule.waitUntil(5000) { model.state.value.applications.singleOrNull()?.revoked == true }
        // A revoked application can be removed permanently, like a revoked device.
        rule.onNodeWithContentDescription("更多操作").performScrollTo().performClick()
        rule.onNodeWithText("删除发送应用").performClick()
        rule
            .onNodeWithText(rule.activity.getString(R.string.admin_delete_application_hint, "NAS"))
            .assertIsDisplayed()
        rule.onAllNodes(hasText("删除发送应用") and hasClickAction()).onLast().performClick()
        rule.waitUntil(5000) { model.state.value.applications.isEmpty() }
        rule
            .onNodeWithText(rule.activity.getString(R.string.admin_no_applications))
            .assertIsDisplayed()
        rule.runOnIdle { screen.value = io.github.beacon.app.ui.components.SettingsScreen.SERVER_ADMIN }
        rule.runOnIdle { model.requestLogout() }
        rule.onNodeWithText(rule.activity.getString(R.string.action_confirm)).performClick()
        rule.waitUntil(5000) { !model.state.value.loggedIn }
        assertNull(model.state.value.secret)
        assertTrue(model.state.value.applications.isEmpty())
    }

    @Test fun materialManagementAndOneTimeCredential() = management(BeaconUiStyle.MATERIAL3)

    @Test fun miuixManagementAndOneTimeCredential() = management(BeaconUiStyle.MIUIX)

    private fun oneTapBinding(style: BeaconUiStyle) {
        val backend = FixtureAdmin()
        val model = ServerAdminViewModel { _, _ -> backend }
        val bound = androidx.compose.runtime.mutableStateOf("")
        var bindCalls = 0
        rule.runOnUiThread {
            androidx.lifecycle
                .ViewModelProvider(
                    rule.activity,
                    object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(
                            modelClass: Class<T>
                        ): T = model as T
                    },
                )[ServerAdminViewModel::class.java]
            rule.activity.setContent {
                BeaconTheme(style) {
                    ServerAdminPage(
                        bound.value,
                        rememberLazyListState(),
                        Modifier,
                        PaddingValues(16.dp),
                        { endpoint, code, name ->
                            assertEquals("https://example.invalid", endpoint)
                            assertEquals("b".repeat(32), code)
                            assertEquals(android.os.Build.MODEL.take(32), name)
                            bindCalls++
                            bound.value = endpoint
                        },
                        canBindCurrent = true,
                        localBindingResult = if (bound.value.isEmpty()) "" else "本机已绑定",
                        onFeedback = {},
                        screen = screen.value, onNavigate = { screen.value = it },
                    )
                }
            }
            model.login("https://example.invalid", "a".repeat(64))
        }
        rule.waitUntil(5000) { model.state.value.loggedIn && !model.state.value.busy }
        rule.onNodeWithTag("server-admin-list").performScrollToNode(hasText("设备") and hasClickAction())
        rule.onNode(hasText("设备") and hasClickAction()).performClick()
        rule.onNodeWithTag("server-admin-list").performScrollToNode(hasText("一键绑定当前手机"))
        rule.onNodeWithText("一键绑定当前手机").assertIsEnabled().performClick()
        rule.waitUntil(5000) { bound.value.isNotEmpty() }
        rule.onNodeWithText("本机已绑定").assertIsDisplayed()
        rule.onNodeWithText("一键绑定当前手机").assertDoesNotExist()
        assertEquals(1, bindCalls)
        assertEquals(1, backend.bindingCalls)
        assertNull(model.state.value.secret)
    }

    @Test fun materialUnboundPhoneBindsInOneTap() = oneTapBinding(BeaconUiStyle.MATERIAL3)

    @Test fun miuixUnboundPhoneBindsInOneTap() = oneTapBinding(BeaconUiStyle.MIUIX)

    private fun refinedPages(style: BeaconUiStyle) {
        val backend = FixtureAdmin(includeApplications = true)
        val model = ServerAdminViewModel { _, _ -> backend }
        rule.runOnUiThread {
            // MainActivity may already own the production model; install this isolated fixture first.
            rule.activity.viewModelStore.clear()
            androidx.lifecycle.ViewModelProvider(rule.activity,
                object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = model as T
                })[ServerAdminViewModel::class.java]
            model.restoreSession(rule.activity)
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    BeaconTheme(style) {
                        val adminState = model.state.collectAsState().value
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (screen.value == io.github.beacon.app.ui.components.SettingsScreen.SERVER_ADMIN)
                                    io.github.beacon.app.feature.settings.AdminOverviewActions(adminState, model)
                                else if (screen.value in listOf(io.github.beacon.app.ui.components.SettingsScreen.SERVER_DEVICES,
                                        io.github.beacon.app.ui.components.SettingsScreen.SERVER_APPLICATIONS))
                                    io.github.beacon.app.feature.settings.AdminManagementActions(adminState, model,
                                        screen.value == io.github.beacon.app.ui.components.SettingsScreen.SERVER_APPLICATIONS)
                            }
                            ServerAdminPage("", rememberLazyListState(), Modifier.weight(1f), PaddingValues(16.dp),
                                { _, _, _ -> }, {}, screen = screen.value, onNavigate = { screen.value = it })
                        }
                    }
                }
            }
        }
        rule.waitUntil(5000) { !model.state.value.restoring }
        rule.runOnIdle { model.login("https://example.invalid", "a".repeat(64)) }
        rule.waitUntil(5000) { model.state.value.loggedIn && !model.state.value.busy }
        val screens = listOf(io.github.beacon.app.ui.components.SettingsScreen.SERVER_ADMIN,
            io.github.beacon.app.ui.components.SettingsScreen.SERVER_DEVICES,
            io.github.beacon.app.ui.components.SettingsScreen.SERVER_APPLICATIONS,
            io.github.beacon.app.ui.components.SettingsScreen.SERVER_TEST)
        screens.forEach { target ->
            rule.runOnIdle { screen.value = target }
            rule.waitForIdle()
            rule.onNodeWithTag("server-admin-list").assertIsDisplayed()
            if (target in listOf(io.github.beacon.app.ui.components.SettingsScreen.SERVER_DEVICES,
                    io.github.beacon.app.ui.components.SettingsScreen.SERVER_APPLICATIONS)) {
                rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_refresh)).assertDoesNotExist()
                rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_filter_title)).assertExists()
                val before = backend.overviewCalls.get()
                rule.onNodeWithTag("server-admin-list").performTouchInput { swipeDown(durationMillis = 600) }
                rule.waitUntil(10000) { backend.overviewCalls.get() > before && !model.state.value.busy }
                rule.mainClock.advanceTimeBy(1500)
                rule.waitForIdle()
            }
            if (target == io.github.beacon.app.ui.components.SettingsScreen.SERVER_TEST) {
                rule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
                rule.onNodeWithText(rule.activity.getString(R.string.admin_send_test)).performScrollTo().assertIsNotEnabled()
            }
            val shot = rule.onRoot().captureToImage().asAndroidBitmap()
            val file = java.io.File(rule.activity.getExternalFilesDir(null), "refined-$style-$target.png")
            file.outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}").use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
        }
        rule.runOnIdle { screen.value = io.github.beacon.app.ui.components.SettingsScreen.SERVER_DEVICES }
        rule.waitForIdle()
        rule.runOnIdle { model.showCreation() }
        rule.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("Synthetic phone")
        rule.onAllNodes(hasSetTextAction()).onLast().performImeAction()
        rule.onNode(hasText(rule.activity.getString(R.string.admin_create_binding)) and hasClickAction()).performClick()
        rule.waitUntil(5000) { model.state.value.secret != null }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_copy)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_complete)).performClick()
        rule.waitUntil(5000) { model.state.value.secret == null }
        rule.runOnIdle { screen.value = io.github.beacon.app.ui.components.SettingsScreen.SERVER_ADMIN }
        rule.waitForIdle()
        val refresh=rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_refresh)).fetchSemanticsNode().boundsInRoot
        val logout=rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_logout)).fetchSemanticsNode().boundsInRoot
        assertTrue("logout is to the right of refresh",logout.center.x > refresh.center.x)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_logout)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).performClick()
        assertTrue(model.state.value.loggedIn)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.admin_logout)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.action_confirm)).performClick()
        rule.waitUntil(5000) { !model.state.value.loggedIn }
    }

    @Test fun refinedMaterialPages() = refinedPages(BeaconUiStyle.MATERIAL3)
    @Test fun refinedMiuixPages() = refinedPages(BeaconUiStyle.MIUIX)

    private class FixtureAdmin(private val includeApplications: Boolean = false) : ServerAdministration {
        val overviewCalls = java.util.concurrent.atomic.AtomicInteger()
        var bindingCalls = 0
        override val endpoint = "https://example.invalid"

        override fun overview() =
            JSONObject(
                """
            {"server_id":"test-server","version":"1.0.0","protocol":1,"admin_protocol":1,
            "devices":[{"device_id":"phone-1","name":"我的小米手机 · 日常消息接收设备","revoked":false}],"applications":[],
            "storage":{"messages":12,"pending":2,"database_allocated_bytes":4096}}
        """
            ).apply {
                overviewCalls.incrementAndGet()
                if (includeApplications) put("applications", org.json.JSONArray().put(JSONObject()
                    .put("id", "app-1").put("device_id", "phone-1").put("name", "工作消息")
                    .put("default_priority", 5).put("revoked", false)))
            }

        override fun binding(name: String): JSONObject {
            bindingCalls++
            return JSONObject()
                .put("binding_code", "b".repeat(32))
                .put("expires_at_ms", System.currentTimeMillis() + 600000)
        }

        override fun revokeDevice(id: String) = JSONObject().put("revoked", true)

        override fun test(id: String, title: String, body: String, priority: Int) =
            JSONObject().put("id", "message-1")

        override fun application(id: String, name: String, priority: Int) =
            JSONObject().put("id", "app-1").put("name", name).put("token", "c".repeat(64))

        override fun revokeApplication(id: String) = JSONObject().put("revoked", true)

        override fun deleteApplication(id: String) =
            JSONObject().put("deleted", 1).put("application_deleted", true)
    }
}
