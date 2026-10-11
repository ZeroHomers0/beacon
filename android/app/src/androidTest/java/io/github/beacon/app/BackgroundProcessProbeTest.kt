package io.github.beacon.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.RuntimeControlClient
import io.github.beacon.app.ipc.RuntimeProtocol
import io.github.beacon.app.ipc.request
import io.github.beacon.app.runtime.RealtimeServiceLauncher
import io.github.beacon.app.runtime.RuntimeProbeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in two-phase fixture for an external driver that kills only the cached UI process. */
class BackgroundProcessProbeTest {
    @Test fun externalBackgroundProbe() {
        val mode = InstrumentationRegistry.getArguments().getString("background_probe")
        assumeTrue(mode == "prepare" || mode == "verify")
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        var endpoint: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                endpoint = binder
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, RuntimeProbeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            val binder = requireNotNull(endpoint)
            val client = RuntimeControlClient(binder)
            val fixture = context.getSharedPreferences("background-process-probe", Context.MODE_PRIVATE)
            if (mode == "prepare") {
                client.transport(TransportSettings("http://127.0.0.1:9", "a".repeat(64)), false)
                val plan = ReceivePlan(ReceiveMode.RULES, false, false)
                client.receivePlan(plan)
                client.setPolling(PollingConfig(enabled = true))
                binder.request(RuntimeProtocol.RECONCILE_POLLING) { Unit }
                RealtimeServiceLauncher.apply(context, plan, true)
                assertTrue(fixture.edit().putLong("preparedAt", System.currentTimeMillis()).commit())
                binder.request(RuntimeProtocol.SCHEDULE_TEST) { Unit }
            } else {
                val preparedAt = fixture.getLong("preparedAt", Long.MAX_VALUE)
                val state = client.state()
                assertTrue("Background worker did not persist an attempt", state.lastAttemptMs >= preparedAt)
                assertEquals(SyncOutcome.TRANSIENT, state.lastOutcome)
                client.setPolling(state.config.copy(enabled = false))
                binder.request(RuntimeProtocol.RECONCILE_POLLING) { Unit }
                fixture.edit().clear().commit()
            }
        } finally {
            context.unbindService(connection)
            activity.close()
        }
    }
}
