package io.github.beacon.app.core

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Preserve actor ordering while releasing the thread and its stack after idle work. */
internal fun serialExecutor(name: String): ThreadPoolExecutor =
    ThreadPoolExecutor(
            1,
            1,
            15,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            { task -> Thread(task, name) },
        )
        .apply { allowCoreThreadTimeOut(true) }
