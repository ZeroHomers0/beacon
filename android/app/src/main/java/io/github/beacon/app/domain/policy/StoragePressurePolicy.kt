package io.github.beacon.app.domain.policy

/** Keep a reserve for confirmation and cleanup writes; never delete unconfirmed messages. */
object StoragePressurePolicy {
    const val RESERVE_BYTES = 32L * 1024 * 1024

    fun allowsNewMessage(availableBytes: Long): Boolean = availableBytes >= RESERVE_BYTES
}
