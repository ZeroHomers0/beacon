package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.RecoverySnapshot
import io.github.beacon.app.domain.model.RecoveryStatus

/**
 * Combines the durable recovery record with the device-protected note left behind by a locked boot.
 *
 * A locked boot cannot write credential-encrypted storage, so the note lives in device-protected
 * storage and is only meaningful while no real record exists for it. Once an unlocked start writes
 * a genuine result, the marker is cleared and this merge becomes an identity.
 */
object RecoveryStatusPolicy {
    fun merge(encrypted: RecoverySnapshot, lockedBootPending: Boolean): RecoverySnapshot {
        encrypted.validate()
        if (!lockedBootPending) return encrypted
        // A locked read cannot reach credential-encrypted storage at all, so the fallback failure
        // snapshot must not be shown as a settings error while a parked wait is what really
        // happened.
        if (
            encrypted.status != RecoveryStatus.NONE &&
                encrypted.status != RecoveryStatus.SETTINGS_ERROR
        )
            return encrypted
        return RecoverySnapshot(RecoveryStatus.AWAITING_UNLOCK, encrypted.atMs, encrypted.attempts)
    }
}
