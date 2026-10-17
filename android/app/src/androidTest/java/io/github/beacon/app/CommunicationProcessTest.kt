package io.github.beacon.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.ipc.RuntimeProtocol
import io.github.beacon.app.ipc.request
import io.github.beacon.app.runtime.RuntimeProbeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CommunicationProcessTest {
    @Test fun serviceRunsInSeparateProcessAndRejectsBlockingMainThreadRequests() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CountDownLatch(1)
        var service: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = binder
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, RuntimeProbeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            val binder = requireNotNull(service)
            binder.request(RuntimeProtocol.PING) {
                assertNotEquals(Process.myPid(), it.readInt())
                assertEquals(BuildConfig.VERSION_CODE, it.readInt())
                assertEquals(context.packageName + ":runtime", it.readString())
            }
            binder.request(RuntimeProtocol.RECONCILE_POLLING) { Unit }
            assertThrows(IllegalStateException::class.java) {
                (context.applicationContext as BeaconApplication).workManagerConfiguration
            }
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SERVICES or android.content.pm.PackageManager.GET_RECEIVERS or android.content.pm.PackageManager.GET_PROVIDERS
            )
            (packageInfo.services.orEmpty().map { it.name to it.processName } +
                packageInfo.receivers.orEmpty().map { it.name to it.processName })
                .filter { it.first.startsWith("androidx.work.") || it.first.startsWith("io.github.beacon.app.runtime.") }
                .forEach { (name, process) -> assertEquals(name, context.packageName + ":runtime", process) }
            val startup = packageInfo.providers.orEmpty().single { it.name == "androidx.startup.InitializationProvider" }
            val provider = context.packageManager.getProviderInfo(
                ComponentName(context.packageName, startup.name), android.content.pm.PackageManager.GET_META_DATA
            )
            assertFalse(provider.metaData.containsKey("androidx.work.WorkManagerInitializer"))
            instrumentation.runOnMainSync {
                assertThrows(IllegalStateException::class.java) {
                    binder.request(RuntimeProtocol.PING) { Unit }
                }
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
