package io.github.beacon.app

import android.os.IBinder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.ipc.RuntimeProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RuntimeRefreshBatchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate()) {
            if (System.nanoTime() >= deadline) fail("Timed out: $label")
            Thread.sleep(10)
        }
    }

    @Test fun burstsProduceOneReadAndInFlightHintsProduceOneTrailingRead() {
        lateinit var repository: RuntimeRepository
        main { repository = RuntimeRepository().also { it.start(instrumentation.targetContext) } }
        val release = CountDownLatch(1)
        try {
            val inFlight = RuntimeRepository::class.java.getDeclaredField("receivePlanInFlight").apply { isAccessible = true }
            val scheduled = RuntimeRepository::class.java.getDeclaredField("receivePlanRefreshJob").apply { isAccessible = true }
            await("startup settled") {
                var ready = false
                main { ready = repository.state.value.receivePlanLoaded && !repository.state.value.messageBusy &&
                    !inFlight.getBoolean(repository) && scheduled.get(repository) == null }
                ready
            }
            Thread.sleep(500) // Let the service's initial observation broadcast finish.
            val connectionField = RuntimeRepository::class.java.getDeclaredField("connection").apply { isAccessible = true }
            val connection = connectionField.get(repository)
            val binderField = connection.javaClass.getDeclaredField("binder").apply { isAccessible = true }
            val real = binderField.get(connection) as IBinder
            val calls = AtomicInteger()
            val block = AtomicBoolean(false)
            val entered = CountDownLatch(1)
            val recording = object : IBinder by real {
                override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == RuntimeProtocol.RECEIVE_PLAN) {
                        calls.incrementAndGet()
                        if (block.compareAndSet(true, false)) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                    return real.transact(code, data, reply, flags)
                }
            }
            main {
                binderField.set(connection, recording)
                repeat(20) { repository.receivePlan() }
                assertEquals(0, calls.get())
            }
            await("batched read") { calls.get() == 1 }
            Thread.sleep(200)
            assertEquals(1, calls.get())

            block.set(true)
            main { repository.receivePlan() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            main { repeat(20) { repository.receivePlan() } }
            release.countDown()
            await("trailing read") { calls.get() == 3 }
            Thread.sleep(200)
            assertEquals(3, calls.get())

            main {
                repository.receivePlan()
                repository.stop()
            }
            Thread.sleep(150)
            assertEquals(3, calls.get())
        } finally {
            release.countDown()
            main { repository.close() }
        }
    }
}
