package io.github.beacon.app.data.local

import android.content.Context
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.CrashRecoveryState
import io.github.beacon.app.domain.policy.RecoveryStatusPolicy

/** Only the reception service serial executor writes this preference file. */
class RecoveryStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences("receive_recovery", Context.MODE_PRIVATE)

    /**
     * A receive process can be restarted by the system while the device is still locked, and then
     * credential-encrypted storage is unreadable. The wait is parked in device-protected storage —
     * writable in either state — and surfaced later.
     */
    private val lockedPreferences by lazy {
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences("receive_recovery_locked_v1", Context.MODE_PRIVATE)
    }

    fun load(): RecoverySnapshot {
        val encrypted = runCatching {
            RecoverySnapshot(
                    RecoveryStatus.valueOf(
                        requireNotNull(preferences.getString("status", RecoveryStatus.NONE.name))
                    ),
                    preferences.getLong("at", 0),
                    preferences.getInt("attempts", 0),
                )
                .also { it.validate() }
        }
            .getOrDefault(RecoverySnapshot(RecoveryStatus.SETTINGS_ERROR))
        return RecoveryStatusPolicy.merge(encrypted, lockedBootPending())
    }

    private fun lockedBootPending(): Boolean = runCatching {
        lockedPreferences.getBoolean("awaiting_unlock", false)
    }
        .getOrDefault(false)

    /** Recorded only while credentials are unavailable; it must never fail the caller. */
    fun recordAwaitingUnlock() {
        runCatching { lockedPreferences.edit().putBoolean("awaiting_unlock", true).commit() }
    }

    /** Reads and clears the parked wait, so an observing service journals it exactly once. */
    fun consumeAwaitingUnlock(): Boolean {
        val pending = lockedBootPending()
        if (pending) runCatching { lockedPreferences.edit().remove("awaiting_unlock").commit() }
        return pending
    }

    fun record(status: RecoveryStatus, attempts: Int = 0) {
        require(attempts >= 0)
        check(
            preferences
                .edit()
                .putString("status", status.name)
                .putLong("at", System.currentTimeMillis())
                .putInt("attempts", attempts)
                .commit()
        )
        // A real result written with credentials available supersedes the parked wait.
        runCatching { lockedPreferences.edit().remove("awaiting_unlock").commit() }
    }
}

/**
 * Persisted unexpected-exit budget. It is deliberately separate from the presentation snapshot: the
 * status shown to the user must survive even when the counter is reset.
 */
class CrashRecoveryStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("crash_recovery_elapsed_v2", Context.MODE_PRIVATE)

    fun load() = runCatching {
        CrashRecoveryState(
                preferences.getInt("attempts", 0),
                preferences.getLong("window", 0),
                preferences.getLong("next", 0),
            )
            .also { it.validate() }
    }
        .getOrDefault(CrashRecoveryState())

    fun save(state: CrashRecoveryState) {
        state.validate()
        check(
            preferences
                .edit()
                .putInt("attempts", state.attempts)
                .putLong("window", state.windowStartMs)
                .putLong("next", state.nextAttemptAtMs)
                .commit()
        )
    }
}
