package io.github.beacon.app.core

import android.app.Application

internal fun currentProcessName(): String? = Application.getProcessName()
