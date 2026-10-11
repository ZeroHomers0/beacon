package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
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

class ServerAdminPageTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun loginValidation(style: BeaconUiStyle, fontScale: Float) {
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
                            {},
                            { _, _, _ -> },
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
        rule
            .onNodeWithText(rule.activity.getString(R.string.admin_invalid_login))
            .performScrollTo()
            .assertIsDisplayed()
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
                            {},
                            { _, _, _ -> },
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
        rule.onNodeWithTag("server-admin-list").performScrollToNode(hasSetTextAction())
        rule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("new phone")
        rule.onAllNodes(hasSetTextAction())[0].performImeAction()
        rule.onNode(hasText("创建绑定码") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(5000) { model.state.value.secret != null }
        rule.onNodeWithText("b".repeat(32)).assertIsDisplayed()
        rule.onAllNodesWithText("一键绑定当前手机").onLast().assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithTag("server-admin-list").performScrollToNode(hasText("发送应用"))
        rule.onNodeWithText("发送应用").performClick().assertIsSelected()
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
            .onNode(hasText("创建发送应用") and hasClickAction())
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        rule.waitUntil(5000) { model.state.value.secret?.example?.isNotEmpty() == true }
        rule.onNodeWithText("c".repeat(64)).assertIsDisplayed()
        rule.onNodeWithText("复制 curl 推送示例").performClick()
        rule.onNodeWithText("已复制").assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule.onNode(hasText("NAS") and !hasSetTextAction()).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("撤销发送凭证").performScrollTo().performClick()
        rule
            .onNodeWithText(rule.activity.getString(R.string.admin_revoke_application_hint, "NAS"))
            .assertIsDisplayed()
        rule.onAllNodes(hasText("撤销发送凭证") and hasClickAction()).onLast().performClick()
        rule.waitUntil(5000) { model.state.value.applications.singleOrNull()?.revoked == true }
        rule.onNodeWithTag("server-admin-list").performScrollToNode(hasText("退出管理"))
        rule.onNodeWithText("退出管理").performClick()
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
                        {},
                        { endpoint, code, name ->
                            assertEquals("https://example.invalid", endpoint)
                            assertEquals("b".repeat(32), code)
                            assertEquals(android.os.Build.MODEL.take(32), name)
                            bindCalls++
                            bound.value = endpoint
                        },
                        canBindCurrent = true,
                        localBindingResult = if (bound.value.isEmpty()) "" else "本机已绑定",
                    )
                }
            }
            model.login("https://example.invalid", "a".repeat(64))
        }
        rule.waitUntil(5000) { model.state.value.loggedIn && !model.state.value.busy }
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

    private class FixtureAdmin : ServerAdministration {
        var bindingCalls = 0
        override val endpoint = "https://example.invalid"

        override fun overview() =
            JSONObject(
                """
            {"server_id":"test-server","version":"1.0.0","protocol":1,"admin_protocol":1,
            "devices":[{"device_id":"phone-1","name":"我的小米手机 · 日常消息接收设备","revoked":false}],"applications":[],
            "storage":{"messages":12,"pending":2,"database_allocated_bytes":4096}}
        """
            )

        override fun binding(name: String): JSONObject {
            bindingCalls++
            return JSONObject()
                .put("binding_code", "b".repeat(32))
                .put("expires_at_ms", System.currentTimeMillis() + 600000)
        }

        override fun revokeDevice(id: String) = JSONObject().put("revoked", true)

        override fun test(id: String, title: String, body: String) =
            JSONObject().put("id", "message-1")

        override fun application(id: String, name: String, priority: Int) =
            JSONObject().put("id", "app-1").put("name", name).put("token", "c".repeat(64))

        override fun revokeApplication(id: String) = JSONObject().put("revoked", true)
    }
}
