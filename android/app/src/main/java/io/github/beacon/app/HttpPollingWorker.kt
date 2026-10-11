package io.github.beacon.app

import android.content.Context
import androidx.work.WorkerParameters

/** Keep this name: existing WorkManager databases persist the original worker class. */
class HttpPollingWorker(context: Context, parameters: WorkerParameters) :
    io.github.beacon.app.runtime.HttpPollingWorker(context, parameters)
