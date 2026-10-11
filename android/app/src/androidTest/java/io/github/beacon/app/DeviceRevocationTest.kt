package io.github.beacon.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.remote.MessageIdentity
import io.github.beacon.app.domain.model.*
import io.github.beacon.app.ipc.*
import io.github.beacon.app.runtime.RuntimeProbeService
import io.github.beacon.app.support.LoopbackServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceRevocationTest {
    @Test
    fun remoteClientBoundsRepliesAndRefusesExpiredOrCancelledRequests() {
        LoopbackServer(201, "{}").use { server ->
            val device =
                BoundDevice(
                    TransportSettings(server.endpoint, "d".repeat(64)),
                    "b".repeat(64),
                    "c".repeat(64),
                    "e".repeat(64),
                )
            val client = io.github.beacon.app.data.remote.HttpDeviceRevocation()
            assertThrows(java.net.SocketTimeoutException::class.java) {
                client.revoke(device, System.nanoTime() - 1)
            }
            assertThrows(java.net.SocketTimeoutException::class.java) {
                client.revoke(device, active = { false })
            }
            assertEquals(0, server.probes.get())
            server.revokeStatus.set(302)
            val rejected =
                assertThrows(
                    io.github.beacon.app.data.remote.DeviceRevocationRejected::class.java
                ) {
                    client.revoke(device)
                }
            assertEquals(302, rejected.status)
            assertEquals(1, server.probes.get())
            server.revokeStatus.set(200)
            server.revokeResponse.set(" ".repeat(4097))
            assertThrows(io.github.beacon.app.data.remote.DeviceRevocationUnconfirmed::class.java) {
                client.revoke(device)
            }
            server.revokeResponse.set("{bad}")
            assertThrows(io.github.beacon.app.data.remote.DeviceRevocationUnconfirmed::class.java) {
                client.revoke(device)
            }
        }
    }

    @Test
    fun confirmedRevocationClearsOnlyOwnReceiptsAndKeepsHistoryWhileFailuresRetainConfig() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val bound = CountDownLatch(1)
        var binder: IBinder? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, value: IBinder) {
                    binder = value
                    bound.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
        val serverId = "b".repeat(64)
        val deviceId = "c".repeat(64)
        val initial =
            JSONObject()
                .put("protocol", 1)
                .put("server_id", serverId)
                .put("device_id", deviceId)
                .put("receive_token", "d".repeat(64))
                .put("push_key", "e".repeat(64))
                .toString()
        LoopbackServer(201, initial).use { server ->
            assertTrue(
                base.bindService(
                    Intent(base, RuntimeProbeService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            )
            try {
                assertTrue(bound.await(15, TimeUnit.SECONDS))
                val remote = requireNotNull(binder)
                val control = RuntimeControlClient(remote)
                control.setPolling(PollingConfig(enabled = false))
                assertEquals(
                    SyncOutcome.OK,
                    control
                        .bindDevice(BindingRequest(server.endpoint, "a".repeat(32), "revoke-test"))
                        .outcome,
                )
                val namespace = MessageIdentity.namespace(serverId, deviceId)
                val foreign = MessageIdentity.namespace(serverId, "f".repeat(64))
                val id = "revoke-" + java.util.UUID.randomUUID()
                RuntimeMessageStore(base).use { store ->
                    store.save(IncomingMessage(namespace, id, "preserved", "body"))
                    store.save(IncomingMessage(foreign, id, "other", "body"))
                    try {
                        for (status in listOf(404, 401, 500)) {
                            server.revokeStatus.set(status)
                            assertEquals(SyncOutcome.CONFIG, control.revokeDevice().outcome)
                            assertEquals(server.endpoint, control.address())
                            assertTrue(store.pendingIds(namespace).contains(id))
                        }
                        server.revokeStatus.set(200)
                        server.revokeResponse.set(
                            JSONObject()
                                .put("revoked", true)
                                .put("server_id", serverId)
                                .put("device_id", "f".repeat(64))
                                .toString()
                        )
                        assertEquals(SyncOutcome.CONFIG, control.revokeDevice().outcome)
                        assertEquals(server.endpoint, control.address())
                        assertTrue(store.pendingIds(namespace).contains(id))
                        // Reject caller payload: device identity comes exclusively from encrypted
                        // worker storage.
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
                            data.writeInt(1)
                            try {
                                remote.transact(RuntimeProtocol.REVOKE_DEVICE, data, reply, 0)
                                assertThrows(SecurityException::class.java) {
                                    reply.readException()
                                }
                            } catch (_: SecurityException) {}
                        } finally {
                            data.recycle()
                            reply.recycle()
                        }
                        server.revokeResponse.set(
                            JSONObject()
                                .put("revoked", true)
                                .put("server_id", serverId)
                                .put("device_id", deviceId)
                                .toString()
                        )
                        control.setPolling(PollingConfig(enabled = true))
                        val result = control.revokeDevice()
                        assertEquals(SyncOutcome.OK, result.outcome)
                        assertFalse(result.state.config.enabled)
                        assertEquals("", control.address())
                        assertFalse(store.pendingIds(namespace).contains(id))
                        assertTrue(store.pendingIds(foreign).contains(id))
                        assertNotNull(store.detail(namespace, id))
                        assertNotNull(store.detail(foreign, id))
                        val posted = JSONObject(server.revokePosted.get())
                        assertEquals("Bearer " + "d".repeat(64), server.revokeAuth.get())
                        assertEquals(2, posted.length())
                        assertEquals(deviceId, posted.getString("device_id"))
                        assertTrue(
                            RuntimeEventClient(remote).read().any {
                                it.kind == RuntimeEventKind.DEVICE_REVOKED
                            }
                        )
                        val probes = server.probes.get()
                        assertEquals(SyncOutcome.CONFIG, control.revokeDevice().outcome)
                        assertEquals(probes, server.probes.get())
                    } finally {
                        store.remove(namespace, id)
                        store.remove(foreign, id)
                        store.ackCompleted(namespace, id)
                        store.ackCompleted(foreign, id)
                    }
                }
                control.clearConnection()
            } finally {
                base.unbindService(connection)
            }
        }
    }
}
