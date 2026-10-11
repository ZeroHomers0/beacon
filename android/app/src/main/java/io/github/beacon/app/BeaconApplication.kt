package io.github.beacon.app

import android.app.Application
import androidx.work.Configuration
import io.github.beacon.app.core.currentProcessName

/** No UI or reception initialization here; WorkManager is created on demand in :runtime only. */
class BeaconApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() {
            check(currentProcessName(this) == packageName + ":runtime") {
                "WorkManager must only be initialized in the communication process"
            }
            return Configuration.Builder().setDefaultProcessName(packageName + ":runtime").build()
        }
}
