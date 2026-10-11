package io.github.beacon.app.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Durable single pending restart for bounded unexpected-exit recovery.
 *
 * It is deliberately elapsed-time based: the recovery budget is measured on the wall clock (which
 * can jump), but the wait itself must not be lengthened or shortened by a clock change. The wakeup
 * is inexact, so a dozing device may run it later than requested; that is reported as a limitation
 * rather than hidden behind an exact-alarm permission.
 */
object CrashRecoveryAlarm {
    private const val REQUEST_CODE = 910

    private fun pending(context: Context) =
        PendingIntent.getForegroundService(
            context,
            REQUEST_CODE,
            Intent(context, RuntimeProbeService::class.java)
                .setAction(RuntimeProbeService.RECOVERY_RETRY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun schedule(context: Context, delayMs: Long) {
        require(delayMs > 0)
        val manager = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delayMs
        // A repeated attempt replaces the pending one instead of queuing several.
        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                at,
                pending(context),
            )
        }
            .onFailure { manager.set(AlarmManager.ELAPSED_REALTIME, at, pending(context)) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
    }
}
