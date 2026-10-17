package io.github.beacon.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.local.TransportSecretStore
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

class CredentialRotationTest {
    @Test
    fun realBinderVerifiesRotationAndPreservesPausedHistoryAndReceipt() {
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
                        .bindDevice(
                            BindingRequest(server.endpoint, "a".repeat(32), "rotation-test")
                        )
                        .outcome,
                )
                val before = control.state().config
                val namespace = MessageIdentity.namespace(serverId, deviceId)
                val id = "rotation-" + java.util.UUID.randomUUID()
                RuntimeMessageStore(base).use { store ->
                    store.save(IncomingMessage(namespace, id, "preserved", "body"))
                    try {
                        val payload =
                            JSONObject()
                                .put("server_id", serverId)
                                .put("device_id", deviceId)
                                .put("receive_token", "f".repeat(64))
                                .put("push_key", "1".repeat(64))
                        server.acceptedToken.set("f".repeat(64))
                        assertEquals(
                            SyncOutcome.OK,
                            control.rotateCredentials(payload.toString()).outcome,
                        )
                        assertEquals(before, control.state().config)
                        assertFalse(control.state().config.enabled)
                        assertEquals("1".repeat(64), control.pushKey())
                        assertNotNull(store.detail(namespace, id))
                        assertTrue(store.pendingIds(namespace).contains(id))
                        val probes = server.probes.get()
                        assertEquals(
                            SyncOutcome.CONFIG,
                            control.rotateCredentials(payload.toString()).outcome,
                        )
                        assertEquals(probes, server.probes.get())
                        val foreign =
                            JSONObject(payload.toString()).put("device_id", "2".repeat(64))
                        assertEquals(
                            SyncOutcome.CONFIG,
                            control.rotateCredentials(foreign.toString()).outcome,
                        )
                        assertEquals(probes, server.probes.get())
                        val rejected =
                            JSONObject(payload.toString())
                                .put("receive_token", "3".repeat(64))
                                .put("push_key", "4".repeat(64))
                        assertEquals(
                            SyncOutcome.CONFIG,
                            control.rotateCredentials(rejected.toString()).outcome,
                        )
                        assertEquals("1".repeat(64), control.pushKey())
                        assertFalse(control.state().config.enabled)
                        assertTrue(
                            RuntimeEventClient(remote).read().any {
                                it.kind == RuntimeEventKind.CREDENTIALS_ROTATED
                            }
                        )
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
                            data.writeString(rejected.toString())
                            data.writeInt(1)
                            try {
                                remote.transact(RuntimeProtocol.ROTATE_CREDENTIALS, data, reply, 0)
                                assertThrows(SecurityException::class.java) {
                                    reply.readException()
                                }
                            } catch (_: SecurityException) {}
                        } finally {
                            data.recycle()
                            reply.recycle()
                        }
                        assertNotNull(store.detail(namespace, id))
                    } finally {
                        store.remove(namespace, id)
                    }
                }
                control.clearConnection()
            } finally {
                base.unbindService(connection)
            }
        }
    }

    @Test
    fun encryptedRotationRejectsUnboundOrForeignIdentityBeforeVerification() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "rotation-secret-" + java.util.UUID.randomUUID()
        val isolated =
            object : android.content.ContextWrapper(base) {
                override fun getSharedPreferences(file: String, mode: Int) =
                    base.getSharedPreferences(name, mode)
            }
        try {
            val store = TransportSecretStore(isolated)
            val old = TransportSettings("https://example.invalid", "a".repeat(64))
            val candidate =
                RotatedDeviceCredentials(
                    "b".repeat(64),
                    "c".repeat(64),
                    "d".repeat(64),
                    "e".repeat(64),
                )
            var verified = false
            store.save(old)
            assertThrows(IllegalArgumentException::class.java) {
                store.rotate(candidate) { verified = true }
            }
            assertFalse(verified)
            store.saveBound(
                BoundDevice(old, candidate.serverId, candidate.deviceId, "f".repeat(64))
            )
            assertThrows(java.io.IOException::class.java) {
                store.rotate(candidate) { throw java.io.IOException("injected probe failure") }
            }
            assertEquals(old, TransportSecretStore(isolated).load())
            assertEquals("f".repeat(64), store.pushKey())
            store.rotate(candidate) { assertEquals(old.endpoint, it.settings.endpoint) }
            assertEquals(candidate.receiveToken, TransportSecretStore(isolated).load().token)
            assertFalse(
                base.getSharedPreferences(name, 0).all.values.any {
                    it.toString().contains(candidate.receiveToken)
                }
            )
        } finally {
            base.deleteSharedPreferences(name)
        }
    }
}
