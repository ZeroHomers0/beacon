package io.github.beacon.app.feature.diagnostics

import android.content.Context
import io.github.beacon.app.R

/**
 * Resolves [SupportReportText] from resources. Kept outside [SupportReport] so the report builder
 * stays free of Android types and remains testable on a plain JVM.
 */
class AndroidSupportReportText(private val context: Context) : SupportReportText {
    private fun id(resource: Int) = context.getString(resource)

    override val title: String
        get() = id(R.string.diag_report_title)

    override val transportNote: String
        get() = id(R.string.diag_transport_hint)

    override val disclaimer: String
        get() = id(R.string.diag_report_disclaimer_hint)

    override val notRecorded: String
        get() = id(R.string.diag_not_recorded_label)

    override fun generatedAt(time: String) =
        context.getString(R.string.diag_report_generated_at, time)

    // Booleans are stringified explicitly: the resources use %s, and their previous Kotlin
    // interpolation rendered "true"/"false".
    override fun appCode(versionCode: Int, debug: Boolean, sdk: Int) =
        context.getString(R.string.diag_report_app_code, versionCode, debug.toString(), sdk)

    override fun runtimeConnection(connected: Boolean, notificationsEnabled: Boolean) =
        context.getString(
            R.string.diag_report_runtime_connection,
            connected.toString(),
            notificationsEnabled.toString(),
        )

    override fun autoReceive(enabled: Boolean, shouldSchedule: Boolean) =
        context.getString(
            R.string.diag_report_auto_receive_switch,
            enabled.toString(),
            shouldSchedule.toString(),
        )

    override fun intervalConditions(minutes: Int, unmetered: Boolean, charging: Boolean) =
        context.getString(
            R.string.diag_report_interval_conditions,
            minutes,
            unmetered.toString(),
            charging.toString(),
        )

    override fun autoBlocked(blocked: Boolean) =
        context.getString(R.string.diag_report_auto_blocked, blocked.toString())

    override fun lastAttempt(time: String) =
        context.getString(R.string.diag_report_last_attempt, time)

    override fun lastSuccess(time: String) =
        context.getString(R.string.diag_report_last_success, time)

    override fun lastResult(outcome: String) =
        context.getString(R.string.diag_report_last_result, outcome)

    override fun lastManual(outcome: String) =
        context.getString(R.string.diag_report_last_manual, outcome)

    override fun receiveMode(mode: String, realtimeRunning: Boolean) =
        context.getString(R.string.diag_report_receive_mode, mode, realtimeRunning.toString())

    override fun realtimeFailure(failure: String, attempts: Int, retries: Int) =
        context.getString(R.string.diag_report_realtime_failure, failure, attempts, retries)

    override fun lastRealtimeConnection(time: String) =
        context.getString(R.string.diag_report_realtime_connected, time)

    override fun recovery(status: String, time: String, attempts: Int) =
        context.getString(R.string.diag_report_recovery, status, time, attempts)

    override val storageNotRead: String
        get() = id(R.string.diag_report_storage_not_read)

    override fun storageSampledAt(time: String) =
        context.getString(R.string.diag_report_storage_sampled, time)

    override fun storageCounts(
        total: Long,
        pending: Long,
        pendingNotifications: Long,
        tombstones: Long,
    ) =
        context.getString(
            R.string.diag_report_storage_counts,
            total,
            pending,
            pendingNotifications,
            tombstones,
        )

    override fun storageBytes(database: Long, reusable: Long, wal: Long) =
        context.getString(R.string.diag_report_storage_bytes, database, reusable, wal)

    override fun storageSpace(available: Long, pressure: String) =
        context.getString(R.string.diag_report_storage_space, available, pressure)
}
