package io.github.beacon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the presentation rules that replaced the status-text comparisons in the screens. */
class OperationStatusTest {
    @Test
    fun pageReadsAndDiagnosticsStayOutOfTheSnackbar() {
        assertFalse(OperationStatus.diagnostic("已读取第 1 页：2 条，匹配共 2 条").showsSnackbar)
        assertFalse(OperationStatus.connecting("正在连接工作进程").showsSnackbar)
        assertFalse(OperationStatus.pending("正在处理消息管理操作").showsSnackbar)
    }

    @Test
    fun resultsAndFailuresReachTheSnackbar() {
        assertTrue(OperationStatus.result("已删除 2 条本地消息").showsSnackbar)
        assertTrue(OperationStatus.connectionLost("工作进程连接超时").showsSnackbar)
    }

    @Test
    fun firstLineIsUsedForCompactContexts() {
        assertEquals("已保存", OperationStatus.result("已保存\n调度未完成").firstLine)
        assertEquals("", OperationStatus.result("").firstLine)
    }

    @Test
    fun defaultStatusAsksForReconnect() {
        val status = RuntimeState().status
        assertEquals(OperationKind.CONNECTION_LOST, status.kind)
        assertEquals("工作进程尚未验证", status.text)
    }
}
