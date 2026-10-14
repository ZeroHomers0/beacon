package io.github.beacon.app.runtime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Payload-free invalidation hints; the UI reads the authenticated Binder snapshot. */
internal object RuntimeUiEvents {
    const val ACTION = "io.github.beacon.app.RUNTIME_CHANGED"
    private val main = Handler(Looper.getMainLooper())
    private var observed = false
    private var pending: Runnable? = null

    @Synchronized
    fun observe(context: Context, enabled: Boolean) {
        observed = enabled
        if (!enabled) {
            pending?.let(main::removeCallbacks)
            pending = null
        } else changed(context)
    }

    @Synchronized
    fun changed(context: Context) {
        if (!observed || pending != null) return
        val app = context.applicationContext
        lateinit var task: Runnable
        task = Runnable {
            synchronized(this) {
                if (pending !== task) return@synchronized
                pending = null
                if (observed) app.sendBroadcast(Intent(ACTION).setPackage(app.packageName))
            }
        }
        pending = task
        main.postDelayed(task, 250L)
    }
}
