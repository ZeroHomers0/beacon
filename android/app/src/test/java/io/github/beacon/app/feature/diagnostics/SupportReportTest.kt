package io.github.beacon.app.feature.diagnostics

import io.github.beacon.app.domain.model.PollingConfig
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.SyncOutcome
import org.junit.Assert.*
import org.junit.Test

/**
 * Report copy now comes from resources, so this JVM test supplies its own [SupportReportText]. The
 * assertions check wiring and leak-safety: which value lands in which slot, and that absent values
 * render as the placeholder rather than "null".
 */
private object Copy : SupportReportText {
    override val title = "Beacon 脱敏诊断报告 v1"
    override val transportNote = "当前接收传输：HTTP / 原生 WebSocket；后台接收受系统调度影响。"
    override val disclaimer = "此报告不包含地址、凭证、消息、设备标识或原始错误。"
    override val notRecorded = "未记录"

    override fun generatedAt(time: String) = "生成时间（UTC）：$time"

    override fun appCode(versionCode: Int, debug: Boolean, sdk: Int) =
        "App code：$versionCode · 开发版：$debug · Android API：$sdk"

    override fun runtimeConnection(connected: Boolean, notificationsEnabled: Boolean) =
        "工作进程连接：$connected · 系统通知开启：$notificationsEnabled"

    override fun autoReceive(enabled: Boolean, shouldSchedule: Boolean) =
        "自动接收开关：$enabled · 调度允许：$shouldSchedule"

    override fun intervalConditions(minutes: Int, unmetered: Boolean, charging: Boolean) =
        "周期：$minutes 分钟 · 仅非计费：$unmetered · 仅充电：$charging"

    override fun autoBlocked(blocked: Boolean) = "存在自动阻断：$blocked"

    override fun lastAttempt(time: String) = "最近自动尝试（UTC）：$time"

    override fun lastSuccess(time: String) = "最近自动成功（UTC）：$time"

    override fun lastResult(outcome: String) = "最近自动结果：$outcome"

    override fun lastManual(outcome: String) = "本次会话最近手动同步：$outcome"

    override fun receiveMode(mode: String, realtimeRunning: Boolean) =
        "接收模式：$mode · 即时连接：$realtimeRunning"

    override fun realtimeFailure(failure: String, attempts: Int, retries: Int) =
        "即时失败类型：$failure · 尝试次数：$attempts · 连续重试：$retries"

    override fun lastRealtimeConnection(time: String) = "最近即时连接（UTC）：$time"

    override fun recovery(status: String, time: String, attempts: Int) =
        "开机/升级恢复：$status · UTC：$time · 本窗口自动恢复尝试：$attempts"

    override val storageNotRead = "存储统计：未读取，请先打开存储与清理并刷新预览"

    override fun storageSampledAt(time: String) = "存储统计（UTC）：$time"

    override fun storageCounts(
        total: Long,
        pending: Long,
        pendingNotifications: Long,
        tombstones: Long,
    ) = "本地消息：$total · 待确认接收：$pending · 待显示通知：$pendingNotifications · 删除去重记录：$tombstones"

    override fun storageBytes(database: Long, reusable: Long, wal: Long) =
        "数据库字节：$database · 可复用字节：$reusable · 临时日志字节：$wal"

    override fun storageSpace(available: Long, pressure: String) =
        "设备剩余空间字节：$available · 空间状态：$pressure"
}

class SupportReportTest {
    @Test
    fun storageSnapshotExportsOnlyCountsAndMeasurementTime() {
        val secret = "opaque-preview-secret"
        val storage =
            io.github.beacon.app.domain.model.CleanupPreview(
                secret,
                4,
                2,
                1,
                8192,
                0,
                2,
                0,
                4096,
                1,
                5,
                512,
                10,
                30,
            )
        val report =
            SupportReport.render(
                SupportSnapshot(
                    48,
                    37,
                    true,
                    true,
                    true,
                    PollingState(),
                    null,
                    storage = storage,
                ),
                40,
                Copy,
            )
        assertFalse(report.contains(secret))
        assertTrue(report.contains("待显示通知：1"))
        assertTrue(report.contains("删除去重记录：5"))
        assertTrue(report.contains("空间状态：LOW"))
        assertTrue(report.contains("1970-01-01T00:00:00.030Z"))
    }

    @Test
    fun untrustedPersistentTextAndIdentitiesNeverEnterReport() {
        val secret = "https://private.example/path?receive_token=" + "a".repeat(64) + " 私密正文"
        val state =
            PollingState(
                PollingConfig(true, 30, true, false),
                secret,
                10,
                20,
                secret,
                lastOutcome = SyncOutcome.AUTH,
            )
        val report =
            SupportReport.render(
                SupportSnapshot(25, 37, true, true, false, state, SyncOutcome.OK),
                30,
                Copy,
            )
        listOf(secret, "private.example", "a".repeat(64), "私密正文").forEach {
            assertFalse(report.contains(it))
        }
        assertTrue(report.contains("最近自动结果：AUTH"))
        assertTrue(report.contains("本次会话最近手动同步：OK"))
        assertTrue(report.contains("调度允许：false"))
    }

    @Test
    fun aRecoveryInProgressReportsHowMuchBudgetItHasSpent() {
        val report =
            SupportReport.render(
                SupportSnapshot(
                    53,
                    37,
                    true,
                    true,
                    true,
                    PollingState(),
                    null,
                    recovery =
                        io.github.beacon.app.domain.model.RecoverySnapshot(
                            io.github.beacon.app.domain.model.RecoveryStatus.CRASH_RETRY_WAITING,
                            1_700_000_000_000L,
                            3,
                        ),
                ),
                30,
                Copy,
            )
        assertTrue(report.contains("开机/升级恢复：CRASH_RETRY_WAITING"))
        assertTrue(report.contains("本窗口自动恢复尝试：3"))
        assertTrue(report.contains("2023-11-14T22:13:20Z"))
    }

    @Test
    fun disconnectedAndLegacyStateDoNotClaimSuccessfulChecks() {
        val report =
            SupportReport.render(
                SupportSnapshot(
                    25,
                    37,
                    false,
                    false,
                    false,
                    PollingState(),
                    null,
                ),
                0,
                Copy,
            )
        assertTrue(report.contains("工作进程连接：false"))
        assertTrue(report.contains("最近自动结果：未记录"))
        assertFalse(report.contains("null"))
    }
}
