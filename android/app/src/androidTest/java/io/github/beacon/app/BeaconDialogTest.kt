package io.github.beacon.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.SyncOutcome
import io.github.beacon.app.ipc.RuntimeProtocol
import io.github.beacon.app.runtime.RuntimeProbeService
import io.github.beacon.app.ui.components.BeaconAlertDialog
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconCheckbox
import io.github.beacon.app.ui.components.BeaconDropdownMenu
import io.github.beacon.app.ui.components.BeaconDropdownMenuItem
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextButton
import io.github.beacon.app.ui.components.BeaconTextField
import io.github.beacon.app.ui.theme.BeaconTheme
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Activity/window: catches missing NavigationEvent owner, which JVM tests cannot.
 */
@RunWith(AndroidJUnit4::class)
class BeaconDialogTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun mount(style: BeaconUiStyle, editor: Boolean = false): AtomicBoolean {
        val confirmed = AtomicBoolean(false)
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    var visible by remember { mutableStateOf(true) }
                    var name by remember { mutableStateOf("") }
                    var checked by remember { mutableStateOf(false) }
                    var choice by remember { mutableStateOf(0) }
                    if (visible)
                        BeaconAlertDialog(
                            onDismissRequest = { visible = false },
                            title = { BeaconText(if (editor) "设置编辑回归" else "诊断弹窗回归") },
                            text = {
                                if (editor)
                                    Column {
                                        BeaconTextField(
                                            name,
                                            { name = it },
                                            label = { BeaconText("本地名称") },
                                            singleLine = true,
                                        )
                                        BeaconCheckbox(checked, { checked = it })
                                        io.github.beacon.app.ui.components.BeaconDropdownPreference(
                                            "测试选项",
                                            listOf("默认", "工作"),
                                            choice,
                                        ) {
                                            choice = it
                                        }
                                    }
                                else BeaconText("不含地址、凭证或真实消息的合成报告")
                            },
                            confirmButton = {
                                BeaconButton(
                                    onClick = {
                                        confirmed.set(true)
                                        visible = false
                                    }
                                ) {
                                    BeaconText("确认")
                                }
                            },
                            dismissButton = {
                                BeaconTextButton(onClick = { visible = false }) { BeaconText("关闭") }
                            },
                        )
                    else BeaconText("已关闭弹窗")
                }
            }
        }
        return confirmed
    }

    @Test
    fun miuixReportDialogOpensAndCloses() {
        mount(BeaconUiStyle.MIUIX)
        rule.onNodeWithText("诊断弹窗回归").assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithText("已关闭弹窗").assertIsDisplayed()
    }

    @Test
    fun miuixEditorSupportsInputAndConfirm() {
        val confirmed = mount(BeaconUiStyle.MIUIX, editor = true)
        rule.onNodeWithText("设置编辑回归").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("测试来源")
        rule.onNodeWithText("测试来源").assertExists()
        rule.onNodeWithText("确认").performClick()
        rule.onNodeWithText("已关闭弹窗").assertIsDisplayed()
        assertTrue(confirmed.get())
    }

    @Test
    fun miuixSystemBackDismissesDialog() {
        mount(BeaconUiStyle.MIUIX)
        rule.onNodeWithText("诊断弹窗回归").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("已关闭弹窗").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun materialReportDialogStillWorks() {
        mount(BeaconUiStyle.MATERIAL3)
        rule.onNodeWithText("诊断弹窗回归").assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithText("已关闭弹窗").assertIsDisplayed()
    }

    @Test fun miuixDialogActionsShareOneRowAtLargeFont()=equalActions(BeaconUiStyle.MIUIX)
    @Test fun materialDialogActionsShareOneRowAtLargeFont()=equalActions(BeaconUiStyle.MATERIAL3)
    private fun equalActions(style:BeaconUiStyle){
        rule.runOnUiThread{rule.activity.setContent{
            val density=androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)
            ){
                BeaconTheme(style){BeaconAlertDialog(
                    onDismissRequest={},title={BeaconText("新建主题")},text={BeaconText("合成测试内容")},
                    confirmButton={androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)){BeaconButton(onClick={}){BeaconText("创建应用")}}},
                    dismissButton={androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,2f)){BeaconTextButton(onClick={}){BeaconText("取消操作")}}},
                )}
            }
        }}
        val cancel=rule.onNode(hasText("取消操作") and hasClickAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val create=rule.onNode(hasText("创建应用") and hasClickAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("same row",cancel.center.y,create.center.y,1f)
        org.junit.Assert.assertEquals("equal width",cancel.width,create.width,1f)
        org.junit.Assert.assertEquals("equal height",cancel.height,create.height,1f)
        org.junit.Assert.assertTrue("cancel on the left",cancel.right<create.left)
        val folder=requireNotNull(rule.activity.getExternalFilesDir("dialog-qa"));folder.mkdirs()
        java.io.File(folder,"equal_${style.name}.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        for(label in listOf("取消操作","创建应用")){
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithText(label).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
            org.junit.Assert.assertTrue(layouts.isNotEmpty())
            val layout=layouts.last()
            org.junit.Assert.assertEquals("complete labels",label.length,layout.getLineEnd(layout.lineCount-1))
            for(line in 0 until layout.lineCount)org.junit.Assert.assertFalse("no truncated labels",layout.isLineEllipsized(line))
            org.junit.Assert.assertTrue("text stays in the button",layout.size.height<=cancel.height)
            org.junit.Assert.assertEquals(13f,layouts.last().layoutInput.style.fontSize.value,.01f)
        }
    }

    private fun mountMenu(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                BeaconTheme(style) {
                    var expanded by remember { mutableStateOf(false) }
                    var selected by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box {
                        BeaconButton(onClick = { expanded = true }) { BeaconText("打开测试菜单") }
                        BeaconDropdownMenu(expanded, { expanded = false }) {
                            BeaconDropdownMenuItem({ BeaconText("测试菜单操作") }) {
                                selected = true
                                expanded = false
                            }
                        }
                    }
                    if (selected) BeaconText("菜单操作已执行")
                }
            }
        }
    }

    @Test
    fun miuixMenuSelectionAndSystemBackWorkInRealWindow() {
        mountMenu(BeaconUiStyle.MIUIX)
        rule.onNodeWithText("打开测试菜单").performClick()
        rule.onNodeWithText("测试菜单操作").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
        rule.waitUntil(5_000) { rule.onAllNodesWithText("测试菜单操作").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("打开测试菜单").performClick()
        rule.onNodeWithText("测试菜单操作").performClick()
        rule.onNodeWithText("菜单操作已执行").assertExists()
    }

    @Test
    fun materialMenuUsesSameDismissibleWindowInteraction() {
        mountMenu(BeaconUiStyle.MATERIAL3)
        rule.onNodeWithText("打开测试菜单").performClick()
        rule.onNodeWithText("测试菜单操作").assertIsDisplayed()
        rule.onNodeWithText("测试菜单操作").performClick()
        rule.onNodeWithText("菜单操作已执行").assertExists()
    }

    @Test
    fun savedAddressCanBeReadWithoutReturningCredential() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val syntheticToken = "ab".repeat(32)
        val connected = java.util.concurrent.CountDownLatch(1)
        var binder: android.os.IBinder? = null
        val connection =
            object : android.content.ServiceConnection {
                override fun onServiceConnected(
                    name: android.content.ComponentName,
                    service: android.os.IBinder,
                ) {
                    binder = service
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: android.content.ComponentName) {}
            }
        check(
            context.bindService(
                android.content.Intent(context, RuntimeProbeService::class.java),
                connection,
                android.content.Context.BIND_AUTO_CREATE,
            )
        )
        try {
            assertTrue(connected.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // Configuration must be written by its owning reception service, never by UI prefs.
            fun configure(address: String, token: String) {
                val request = android.os.Parcel.obtain()
                val response = android.os.Parcel.obtain()
                try {
                    request.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
                    request.writeString(address)
                    request.writeString(token)
                    check(
                        requireNotNull(binder)
                            .transact(RuntimeProtocol.CONFIGURE, request, response, 0)
                    )
                    response.readException()
                    response.readString()
                    response.readString()
                    org.junit.Assert.assertEquals(SyncOutcome.OK.ordinal, response.readInt())
                    org.junit.Assert.assertEquals(0, response.dataAvail())
                } finally {
                    request.recycle()
                    response.recycle()
                }
            }
            configure("https://example.invalid", syntheticToken)
            configure("https://saved.example.invalid", "")
            val data = android.os.Parcel.obtain()
            val reply = android.os.Parcel.obtain()
            try {
                data.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
                check(
                    requireNotNull(binder).transact(RuntimeProtocol.GET_CONNECTION, data, reply, 0)
                )
                reply.readException()
                org.junit.Assert.assertEquals("https://saved.example.invalid", reply.readString())
                org.junit.Assert.assertEquals(0, reply.dataAvail())
            } finally {
                data.recycle()
                reply.recycle()
            }
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun miuixDropdownInsideEditorKeepsParentDialogAndSelection() {
        mount(BeaconUiStyle.MIUIX, editor = true)
        rule.onNodeWithText("测试选项").performClick()
        rule.onNodeWithText("工作").performClick()
        rule.onNodeWithText("工作").assertExists()
        rule.onNodeWithText("设置编辑回归").assertIsDisplayed()
        rule.onNodeWithText("确认").performClick()
        rule.onNodeWithText("已关闭弹窗").assertIsDisplayed()
    }

    private fun assertEqualActionBounds(style: BeaconUiStyle) {
        rule.runOnUiThread {
            rule.activity.setContent {
                val density = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(density.density, fontScale = 1.3f)
                ) {
                    BeaconTheme(style) {
                        BeaconAlertDialog(
                            onDismissRequest = {},
                            title = { BeaconText("来源设置尺寸回归") },
                            text = { BeaconText("大字体下保存与取消保持等宽等高") },
                            dismissButton = { BeaconTextButton(onClick = {}) { BeaconText("取消") } },
                            confirmButton = { BeaconButton(onClick = {}) { BeaconText("保存") } },
                        )
                    }
                }
            }
        }
        val cancel =
            rule.onNode(hasText("取消") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val save = rule.onNode(hasText("保存") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Action widths differ: $cancel / $save",
            kotlin.math.abs(cancel.width - save.width) <= 1f,
        )
        assertTrue(
            "Action heights differ: $cancel / $save",
            kotlin.math.abs(cancel.height - save.height) <= 1f,
        )
        assertTrue("Short actions must remain side by side when they fit", cancel.right < save.left)
    }

    @Test
    fun miuixDialogActionsHaveEqualBoundsAtLargeFont() =
        assertEqualActionBounds(BeaconUiStyle.MIUIX)

    @Test
    fun materialDialogActionsHaveEqualBoundsAtLargeFont() =
        assertEqualActionBounds(BeaconUiStyle.MATERIAL3)
}
