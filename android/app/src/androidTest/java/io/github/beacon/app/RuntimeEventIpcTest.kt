package io.github.beacon.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.domain.policy.RealtimeRetryDecision
import io.github.beacon.app.ipc.*
import io.github.beacon.app.runtime.RuntimeProbeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RuntimeEventIpcTest {

    @Test
    fun realBinderHistoryClearAndPausedRetryPreserveMessagesAndControl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var serviceBinder: IBinder? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    serviceBinder = binder
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
        assertTrue(
            context.bindService(
                Intent(context, RuntimeProbeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        )
        try {
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            val binder = requireNotNull(serviceBinder)
            val control = RuntimeControlClient(binder)
            control.setPolling(PollingConfig(enabled = false))
            val before = control.state()
            val events = RuntimeEventClient(binder)
            events.clear()
            assertTrue(events.read().isEmpty())
            val decision = control.reconnectRealtime()
            assertNotEquals(RealtimeRetryDecision.ALLOWED, decision)
            assertFalse(control.state().config.enabled)
            assertEquals(before.config, control.state().config)
            assertTrue(events.read().isEmpty())
            val rules =
                ReceivePlan(ReceiveMode.RULES, false, false, true, 22 * 60, 7 * 60, false, true, 25)
            assertEquals(rules, control.receivePlan(rules).plan)
            assertEquals(rules, control.receivePlan().plan)
            assertTrue(events.read().any { it.kind == RuntimeEventKind.MODE_SAVED })
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
                data.writeInt(1)
                try {
                    binder.transact(RuntimeProtocol.CLEAR_EVENTS, data, reply, 0)
                    assertThrows(SecurityException::class.java) { reply.readException() }
                } catch (_: SecurityException) {
                    /* In-process Binder surfaces this directly. */
                }
                assertTrue(events.read().any { it.kind == RuntimeEventKind.MODE_SAVED })
            } finally {
                data.recycle()
                reply.recycle()
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
