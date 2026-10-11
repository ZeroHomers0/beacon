package io.github.beacon.app.core

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

internal fun currentProcessName(context: Context): String? =
    if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
    else
        context
            .getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
