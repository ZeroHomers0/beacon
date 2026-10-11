package io.github.beacon.app.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialExecutorTest {
    @Test
    fun idleThreadIsReleasedAndLaterWorkStillRunsInOrder() {
        val executor = serialExecutor("executor-test")
        try {
            executor.setKeepAliveTime(20, TimeUnit.MILLISECONDS)
            val order = mutableListOf<Int>()
            val first = CountDownLatch(1)
            executor.execute {
                order.add(1)
                first.countDown()
            }
            assertTrue(first.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (executor.poolSize != 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(0, executor.poolSize)
            executor.execute { order.add(2) }
            executor
                .submit<List<Int>> { order.toList() }
                .get(3, TimeUnit.SECONDS)
                .let {
                    assertEquals(listOf(1, 2), it)
                }
        } finally {
            executor.shutdownNow()
        }
    }
}
