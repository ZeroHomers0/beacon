package io.github.beacon.app.runtime

import android.content.Context
import android.os.UserManager

/**
 * Whether credential-encrypted storage is readable right now.
 *
 * It is read rather than assumed: the platform normally defers `BOOT_COMPLETED` until after unlock
 * for a build that is not `directBootAware`, but a start can also arrive while the user has not
 * unlocked since boot, and then every credential-encrypted store throws. Reporting a recovery
 * decision in that state would describe work that never ran, so callers gate on this instead.
 */
internal object CredentialAvailability {
    fun unlocked(context: Context): Boolean = runCatching {
        context.getSystemService(UserManager::class.java).isUserUnlocked
    }
        .getOrDefault(true)
}
