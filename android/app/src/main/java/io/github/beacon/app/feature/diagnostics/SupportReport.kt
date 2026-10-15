package io.github.beacon.app.feature.diagnostics

import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.model.PollingState
import io.github.beacon.app.domain.model.SyncOutcome

/** No endpoint, token, identity, message, fingerprint or free-form error text is emitted. */
data class SupportSnapshot(
    val versionCode: Int,
    val sdk: Int,
    val debug: Boolean,
    val runtimeConnected: Boolean,
    val notificationsEnabled: Boolean,
    val polling: PollingState,
    val manualOutcome: SyncOutcome?,
    val receiveMode: ReceiveMode = ReceiveMode.POWER_SAVING,
    val realtimeRunning: Boolean = false,
    val realtimeDiagnostics: RealtimeDiagnostics = RealtimeDiagnostics(),
    val recovery: RecoverySnapshot = RecoverySnapshot(),
    val storage: CleanupPreview? = null,
)

/**
 * Report copy, supplied by the caller so [SupportReport] stays free of Android types and keeps
 * running under plain JVM tests. Android callers use [AndroidSupportReportText].
 */
interface SupportReportText {
    val title: String
    val transportNote: String
    val disclaimer: String
    val notRecorded: String

    fun generatedAt(time: String): String

    fun appCode(versionCode: Int, debug: Boolean, sdk: Int): String

    fun runtimeConnection(connected: Boolean, notificationsEnabled: Boolean): String

    fun autoReceive(enabled: Boolean, shouldSchedule: Boolean): String

    fun intervalConditions(minutes: Int, unmetered: Boolean, charging: Boolean): String

    fun autoBlocked(blocked: Boolean): String

    fun lastAttempt(time: String): String

    fun lastSuccess(time: String): String

    fun lastResult(outcome: String): String

    fun lastManual(outcome: String): String

    fun receiveMode(mode: String, realtimeRunning: Boolean): String

    fun realtimeFailure(failure: String, attempts: Int, retries: Int): String

    fun lastRealtimeConnection(time: String): String

    fun recovery(status: String, time: String, attempts: Int): String

    val storageNotRead: String

    fun storageSampledAt(time: String): String

    fun storageCounts(
        total: Long,
        pending: Long,
        pendingNotifications: Long,
        tombstones: Long,
    ): String

    fun storageBytes(database: Long, reusable: Long, wal: Long): String

    fun storageSpace(available: Long, pressure: String): String
}

object SupportReport {
    private fun time(value: Long, missing: String) =
        if (value <= 0) missing else io.github.beacon.app.domain.policy.DisplayTime.format(value, java.time.ZoneOffset.UTC)

    fun render(value: SupportSnapshot, nowMs: Long, text: SupportReportText): String = buildString {
        appendLine(text.title)
        appendLine(text.generatedAt(time(nowMs, text.notRecorded)))
        appendLine(text.appCode(value.versionCode, value.debug, value.sdk))
        appendLine(text.runtimeConnection(value.runtimeConnected, value.notificationsEnabled))
        appendLine(text.autoReceive(value.polling.config.enabled, value.polling.shouldSchedule))
        appendLine(
            text.intervalConditions(
                value.polling.config.intervalMinutes,
                value.polling.config.unmetered,
                value.polling.config.charging,
            )
        )
        appendLine(text.autoBlocked(value.polling.blocked.isNotEmpty()))
        appendLine(text.lastAttempt(time(value.polling.lastAttemptMs, text.notRecorded)))
        appendLine(text.lastSuccess(time(value.polling.lastSuccessMs, text.notRecorded)))
        appendLine(text.lastResult(value.polling.lastOutcome?.name ?: text.notRecorded))
        appendLine(text.lastManual(value.manualOutcome?.name ?: text.notRecorded))
        val connection = value.realtimeDiagnostics
        appendLine(text.receiveMode(value.receiveMode.name, value.realtimeRunning))
        appendLine(
            text.realtimeFailure(
                connection.failure.name,
                connection.attempts,
                connection.retries,
            )
        )
        appendLine(text.lastRealtimeConnection(time(connection.connectedAtMs, text.notRecorded)))
        appendLine(
            text.recovery(
                value.recovery.status.name,
                time(value.recovery.atMs, text.notRecorded),
                value.recovery.attempts,
            )
        )
        val storage = value.storage
        if (storage == null || storage.sampledAtMs == 0L) {
            appendLine(text.storageNotRead)
        } else {
            appendLine(text.storageSampledAt(time(storage.sampledAtMs, text.notRecorded)))
            appendLine(
                text.storageCounts(
                    storage.total,
                    storage.pending,
                    storage.pendingNotifications,
                    storage.tombstones,
                )
            )
            appendLine(
                text.storageBytes(storage.databaseBytes, storage.reusableBytes, storage.walBytes)
            )
            appendLine(
                text.storageSpace(
                    storage.availableBytes,
                    StorageHealth.pressure(storage.availableBytes).name,
                )
            )
        }
        appendLine(text.transportNote)
        append(text.disclaimer)
    }
}
